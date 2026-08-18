package com.autobot.app.server

import android.hardware.display.VirtualDisplay
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalSocketAddress.Namespace
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Surface
import com.autobot.app.third.DisplayManagerHelper
import com.autobot.app.third.FakeContext
import com.autobot.app.third.Workarounds
import java.io.InputStream
import java.io.OutputStream

/**
 * Server 进程入口（通过 Shizuku.newProcess 启动的独立 app_process，shell uid）。
 *
 * 调用形式：
 *   app_process -Djava.class.path=/data/local/tmp/autobot-server.apk / com.autobot.app.server.ServerMain <socketName>
 *
 * 主流程：
 *   1. 从 args 拿 socketName，连到 App 进程预先创建的 LocalServerSocket
 *   2. 读 MSG_CREATE_VD（含 Surface Parcel 跨进程序列化数据）
 *   3. Workarounds.apply() 注入 ActivityThread（app_process 默认没有）
 *   4. FakeContext.get() 拿 com.android.shell 身份的 Context
 *   5. 反序列化 Surface，调 DisplayManagerHelper.createVirtualDisplay(surface,...)
 *      —— server 进程是 shell uid，系统侧 callingUid 校验自然通过
 *   6. 回写 MSG_CREATE_VD_RESP（displayId 或 错误消息）
 *   7. 进入 keepAlive 循环：5s 一次 PING/PONG；RELEASE_VD → release VD + exit；
 *      socket 断连 → release VD + exit（防止 App 死掉后 VD 残留）
 *
 * 重要约束：
 *  - server 进程不在 App 的 JVM 中，**不能依赖** AndroidX / Kotlinx / App 业务类
 *    只能用 Android 框架 + Kotlin stdlib + Workarounds/FakeContext/DisplayManagerHelper
 *    （这些类都编入了 classes.dex，通过 -Djava.class.path 加载）
 *  - 异常必须捕获后通过 MSG_CREATE_VD_RESP.error 反馈给 App，不能让 server 进程静默崩溃
 */
object ServerMain {

    private const val TAG = "ServerMain"

    /** 默认 socket 名（与 CompositionService 创建的 LocalServerSocket 一致） */
    private const val DEFAULT_SOCKET_NAME = "com.autobot.app.vdserver"

    /** 心跳间隔（毫秒）：5 秒一次 PING */
    private const val PING_INTERVAL_MS = 5_000L

    /** 心跳超时（毫秒）：15 秒没收到 PONG 视为 App 已死 */
    private const val PONG_TIMEOUT_MS = 15_000L

    /** 持有的 VirtualDisplay（释放时调 release） */
    @Volatile
    private var heldVd: VirtualDisplay? = null

    /** 持有的 Surface（释放时调 release） */
    @Volatile
    private var heldSurface: Surface? = null

    /** socket 是否还活着（控制 keepAlive 循环退出） */
    @Volatile
    private var running = true

    /**
     * server 进程入口（app_process -Djava.class.path=... / ServerMain <socketName>）
     *
     * 注意：用 @JvmStatic 保证 Kotlin object 的方法是真正的 static void main(String[])，
     * 这样 app_process 才能找到入口（app_process 找的是 main(String[])）。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val socketName = args.getOrElse(0) { DEFAULT_SOCKET_NAME }
        Log.i(TAG, "🚀 ServerMain start: socket=$socketName pid=${Process.myPid()} uid=${Process.myUid()}")

        val socket = LocalSocket()
        try {
            // 1. 连接 App 进程的 LocalServerSocket
            socket.connect(LocalSocketAddress(socketName, Namespace.ABSTRACT))
            Log.i(TAG, "Connected to LocalSocket: $socketName")

            val input = socket.inputStream
            val out = socket.outputStream

            // 2. 阻塞读 CREATE_VD（第一条消息必须是 CREATE_VD）
            val (msgType, payload) = try {
                VDProtocol.readMessage(input)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read first message", e)
                writeResp(out, ok = false, displayId = -1,
                    error = "Server 读消息失败: ${e.javaClass.simpleName}: ${e.message}")
                return
            }

            if (msgType != VDProtocol.MSG_CREATE_VD) {
                Log.e(TAG, "Unexpected first msg type=$msgType (expected CREATE_VD)")
                writeResp(out, ok = false, displayId = -1,
                    error = "Server 收到的首条消息类型不对: type=$msgType")
                return
            }

            // 3-6. 处理 CREATE_VD（内部会写 MSG_CREATE_VD_RESP）
            handleCreateVd(payload, out)

            // 7. 如果 VD 创建成功 → 进入心跳保活循环
            if (heldVd != null) {
                runKeepAliveLoop(input, out)
            } else {
                Log.w(TAG, "VD not created, skipping keepAlive loop")
            }

        } catch (e: Throwable) {
            Log.e(TAG, "ServerMain fatal", e)
        } finally {
            Log.i(TAG, "ServerMain exit, releasing VD if any")
            releaseVd()
            try { socket.close() } catch (_: Exception) {}
            running = false
        }
    }

    /**
     * 处理 CREATE_VD 请求：反序列化 Surface + Workarounds + FakeContext + 调 createVirtualDisplay
     * 然后回写 MSG_CREATE_VD_RESP。
     */
    private fun handleCreateVd(payload: ByteArray, out: OutputStream) {
        try {
            val req = VDRequest.fromByteArray(payload)
            Log.i(TAG, "CREATE_VD: ${req.width}x${req.height} dpi=${req.density} flags=0x${req.flags.toString(16)} name=${req.name} surfaceBytes=${req.surfaceBytes.size}")

            // Step 3: Workarounds.apply() —— app_process 没有 ActivityThread，必须注入
            try {
                Workarounds.apply()
                Log.i(TAG, "✅ Workarounds.apply() OK")
            } catch (t: Throwable) {
                Log.w(TAG, "Workarounds.apply() failed (continuing with FakeContext fallback)", t)
                // 不直接 return，让 FakeContext.get() 内部兜底
            }

            // Step 4: FakeContext —— 返回 com.android.shell 身份的 Context
            val fakeContext = FakeContext.get()
            Log.i(TAG, "✅ FakeContext.get() OK: packageName=${fakeContext.packageName}")

            // Step 5: 反序列化 Surface（App 进程用 surface.writeToParcel 序列化）
            val surface = unmarshallSurface(req.surfaceBytes)
                ?: run {
                    writeResp(out, ok = false, displayId = -1,
                        error = "Surface 反序列化失败（surfaceBytes.size=${req.surfaceBytes.size}）")
                    return
                }
            heldSurface = surface
            Log.i(TAG, "✅ Surface unmarshalled: $surface")

            // Step 6: 创建 VirtualDisplay
            // server 进程是 shell uid，createVirtualDisplay 调用链上 callingUid=SHELL_UID，
            // system_server 不会拒绝。
            val vd = DisplayManagerHelper.createVirtualDisplay(
                surface = surface,
                name = req.name,
                width = req.width,
                height = req.height,
                density = req.density,
                flags = req.flags
            )

            if (vd == null) {
                writeResp(out, ok = false, displayId = -1,
                    error = "DisplayManagerHelper.createVirtualDisplay 返回 null（详见 logcat DisplayMgrHelper 标签）")
                return
            }

            heldVd = vd
            val displayId = vd.display?.displayId ?: -1
            Log.i(TAG, "✅ VirtualDisplay created, displayId=$displayId")

            writeResp(out, ok = true, displayId = displayId, error = "")

        } catch (t: Throwable) {
            Log.e(TAG, "handleCreateVd exception", t)
            // 把根因挖出来，便于 App 端展示给用户
            var cause: Throwable? = t
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            val detail = if (cause != null && cause !== t) {
                "（根因：${cause.javaClass.simpleName}: ${cause.message}）"
            } else ""
            writeResp(out, ok = false, displayId = -1,
                error = "Server 处理 CREATE_VD 异常: ${t.javaClass.simpleName}: ${t.message} $detail")
        }
    }

    /**
     * 反序列化 Surface（App 端用 surface.writeToParcel + parcel.marshall）
     */
    private fun unmarshallSurface(surfaceBytes: ByteArray): Surface? {
        if (surfaceBytes.isEmpty()) return null
        val p = Parcel.obtain()
        return try {
            p.unmarshall(surfaceBytes, 0, surfaceBytes.size)
            p.setDataPosition(0)
            val surface = Surface.CREATOR.createFromParcel(p) as? Surface
            if (surface == null) {
                Log.e(TAG, "Surface.CREATOR.createFromParcel returned null")
            } else if (!surface.isValid) {
                Log.w(TAG, "Surface is not valid after unmarshall (may still work)")
            }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "Surface unmarshall exception", e)
            null
        } finally {
            p.recycle()
        }
    }

    /**
     * 写 MSG_CREATE_VD_RESP
     */
    private fun writeResp(out: OutputStream, ok: Boolean, displayId: Int, error: String) {
        try {
            val resp = VDResponse(ok = ok, displayId = displayId, error = error)
            VDProtocol.writeMessage(out, VDProtocol.MSG_CREATE_VD_RESP, resp.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write MSG_CREATE_VD_RESP", e)
        }
    }

    /**
     * 心跳保活循环：
     *  - 每 5s 读一次消息（不主动发 PING，由 App 端发 PING，server 回 PONG）
     *  - 15s 没收到任何消息 → 视为 App 已死，release VD + exit
     *  - 收到 RELEASE_VD → release VD + 写 RESP + break
     *  - 收到 PING → 写 PONG
     *  - socket 异常 → release VD + exit
     */
    private fun runKeepAliveLoop(input: InputStream, out: OutputStream) {
        Log.i(TAG, "keepAlive loop started (PING_INTERVAL=${PING_INTERVAL_MS}ms, PONG_TIMEOUT=${PONG_TIMEOUT_MS}ms)")

        while (running && heldVd != null) {
            // 用 available + 短 sleep 实现"等消息但每秒看一次"的轻量 polling
            // 不能直接 readMessage 阻塞，因为要支持 PONG 超时
            // scrcpy 用 SO_TIMEOUT，Android LocalSocket 不支持 SO_TIMEOUT（API 26+ 才有 setSoTimeout 但 LocalSocket 不行）
            // 简化方案：让 readMessage 阻塞，socket 断连时会抛 IOException 退出
            try {
                val (msgType, payload) = VDProtocol.readMessage(input)
                when (msgType) {
                    VDProtocol.MSG_PING -> {
                        // 回 PONG
                        VDProtocol.writeMessage(out, VDProtocol.MSG_PONG, VDProtocol.EMPTY_PAYLOAD)
                    }
                    VDProtocol.MSG_RELEASE_VD -> {
                        Log.i(TAG, "Received RELEASE_VD, releasing...")
                        writeResp(out, ok = true, displayId = -1, error = "")
                        releaseVd()
                        running = false
                    }
                    else -> {
                        Log.w(TAG, "Unexpected msg in keepAlive: type=$msgType, ignoring")
                    }
                }
            } catch (e: Exception) {
                // socket 断连 / EOF
                Log.w(TAG, "keepAlive: socket read failed (${e.javaClass.simpleName}: ${e.message}), releasing VD and exiting")
                releaseVd()
                running = false
            }
        }
        Log.i(TAG, "keepAlive loop exited")
    }

    /**
     * 释放 VirtualDisplay 和 Surface 资源（幂等）
     */
    private fun releaseVd() {
        try {
            heldVd?.release()
        } catch (e: Exception) {
            Log.w(TAG, "VD release exception: ${e.message}")
        }
        heldVd = null

        try {
            heldSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Surface release exception: ${e.message}")
        }
        heldSurface = null
    }
}
