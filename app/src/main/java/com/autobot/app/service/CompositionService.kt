package com.autobot.app.service

import android.content.Context
import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalSocketAddress.Namespace
import android.os.Parcel
import android.util.Log
import android.view.Surface
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.ShizukuProcessManager
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.server.VDProtocol
import com.autobot.app.server.VDRequest
import com.autobot.app.server.VDResponse
import com.autobot.app.third.DisplayManagerHelper
import com.autobot.app.util.ShellExecutor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟显示器合成服务（重构版：通过 Shizuku.newProcess 启动独立 server 进程创建 VD）
 *
 * 架构对齐 scrcpy/MAA-Meow：
 *   - App 进程（本类所在）：持有 NativeCapturer + AImageReader，把 Surface 通过 Parcel
 *     跨进程传给 server 进程
 *   - server 进程（Shizuku.newProcess 启动的 app_process，shell uid）：调用
 *     Workarounds.apply() + FakeContext + DisplayManager.createVirtualDisplay(surface)
 *   - IPC 通道：LocalSocket abstract namespace + 自定义二进制协议 VDProtocol
 *
 * 权限路径：
 *   - 仅依赖 Shizuku 授权（server 进程是 shell uid，持有 MANAGE_DISPLAYS 系统级权限）
 *   - 不需要 MediaProjection 弹窗，不需要用户在运行时确认
 *
 * 兼容性：
 *   - MonitorViewModel 调用的所有 public 方法签名保持不变
 *   - displayId 改为读 cachedDisplayId（VD 在 server 进程内，App 拿不到 VirtualDisplay 对象）
 */
class CompositionService(private val context: Context) {

    companion object {
        private const val TAG = "CompositionSvc"

        // 虚拟显示器默认分辨率（竖屏）
        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 960
        const val DEFAULT_DPI = 240

        // 虚拟显示器名称
        private const val VIRTUAL_DISPLAY_NAME = "AutoBOT-VirtualDisplay"

        // LocalSocket abstract namespace（App 与 server 共享的 socket 名）
        // 不带前导 / 即为 ABSTRACT namespace，避免与 filesystem namespace 冲突
        private const val SOCKET_NAME = "com.autobot.app.vdserver"

        // 心跳间隔（App → Server）：5s 一次 PING
        private const val PING_INTERVAL_MS = 5_000L

        // 心跳超时：连续两次 PONG 没收到（约 12s）视为 server 已死
        private const val PONG_TIMEOUT_MS = 12_000L

        // 等 server 进程连入 LocalServerSocket 的最长等待时间
        private const val SERVER_CONNECT_TIMEOUT_MS = 5_000L

        // 等 MSG_CREATE_VD_RESP 的最长等待时间
        private const val CREATE_VD_RESP_TIMEOUT_MS = 10_000L
    }

    private var capturer: NativeCapturer? = null
    private var displaySurface: Surface? = null

    /** 缓存的预览 Surface（重启 VD 后自动重新绑定） */
    private var cachedPreviewSurface: Surface? = null

    /** LocalServerSocket（App 进程作为 server 端等待 server 进程连入） */
    @Volatile
    private var serverSocket: LocalServerSocket? = null

    /** server 进程连入后拿到的 client socket */
    @Volatile
    private var clientSocket: LocalSocket? = null

    /** 通过 Shizuku.newProcess 启动的 server Process */
    @Volatile
    private var serverProcess: Process? = null

    /** server stdout/stderr 排查日志线程 */
    @Volatile
    private var stdoutThread: Thread? = null
    @Volatile
    private var stderrThread: Thread? = null

    /** 心跳保活线程（App → Server PING） */
    @Volatile
    private var keepAliveThread: Thread? = null

    /** 控制心跳线程退出 */
    private val keepAliveRunning = AtomicBoolean(false)

    /** server 进程返回的 displayId（VD 在 server 进程内，App 端拿不到对象只能缓存 id） */
    @Volatile
    private var cachedDisplayId: Int = -1

    var width: Int = DEFAULT_WIDTH
        private set
    var height: Int = DEFAULT_HEIGHT
        private set

    val isLandscape: Boolean get() = width > height

    /**
     * 虚拟显示器的 Display ID
     * 用于 am start --display <displayId> 让 App 启动到虚拟显示器
     * VD 在 server 进程内创建，App 端只能通过 cachedDisplayId 拿到
     */
    val displayId: Int get() = cachedDisplayId

    /**
     * 启动虚拟显示器
     *
     * 新流程（server 进程架构）：
     * 1. 校验 Shizuku 已授权
     * 2. NativeCapturer.setupNativeCapturer(w, h) 拿到 App 进程内的 Surface1（AImageReader 的输入）
     * 3. 把 Surface1 Parcel 序列化为 surfaceBytes（跨进程通过 socket 传给 server）
     * 4. 创建 LocalServerSocket(SOCKET_NAME) 等待 server 进程连入
     * 5. ShizukuProcessManager.ensureServerApk + launchServer(SOCKET_NAME)
     * 6. serverSocket.accept() 拿 client socket
     * 7. VDProtocol.writeMessage(MSG_CREATE_VD, VDRequest(...).toByteArray())
     * 8. VDProtocol.readMessage → VDResponse，校验 ok=true && displayId>0 → cachedDisplayId = ...
     * 9. 启动 keepAliveThread（5s 一次 PING）
     *
     * @return Pair<Surface?, String> 成功返回 (displaySurface, "")，失败返回 (null, errorMsg)
     */
    fun startVirtualDisplay(width: Int = DEFAULT_WIDTH,
                            height: Int = DEFAULT_HEIGHT): Pair<Surface?, String> {
        if (cachedDisplayId > 0) {
            Log.w(TAG, "VirtualDisplay already running, stop first")
            return displaySurface to ""
        }

        // 步骤 1：Shizuku 权限校验
        val diag = ShizukuManager.diagnoseShizuku(context)
        when (diag) {
            ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED -> {
                val msg = "Shizuku 未安装：请先安装 Shizuku App（rikka.shizuku / moe.shizuku.privileged.api）"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> {
                val msg = "Shizuku 未连接：请打开 Shizuku App 并通过「无线调试」或「ADB 命令」启动服务。" +
                        "注意：用 Root 模式启动 Shizuku 在 Android 12+ 上会触发" +
                        "\"packageName must match the calling uid\" 的 SecurityException，" +
                        "必须用 ADB/无线调试 模式启动 Shizuku（参考 MAA-Meow issue #9）。"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> {
                val msg = "Shizuku 已连接但未授权：请在设置页面点击「授权 Shizuku」按钮，" +
                        "或在 Shizuku App 的「已授权的应用」中手动添加本应用。"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR -> {
                val msg = "Shizuku 状态异常：请重启 Shizuku 服务后重试"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.OK -> {
                Log.i(TAG, "step1 Shizuku OK")
            }
        }

        this.width = width
        this.height = height

        return try {
            // 步骤 2：初始化 Native 层图像读取器，拿到承载画面的 Surface1
            Log.i(TAG, "step2 setupNativeCapturer(${width}x${height}) ...")
            val cap = NativeCapturer()
            val surface = cap.setupNativeCapturer(width, height)
            if (surface == null) {
                val msg = "Native 图像采集器初始化失败：setupNativeCapturer 返回 null。" +
                        "排查：①minSdkVersion 是否 >= 26（AImageReader 要求） ②libautobot_native.so 是否被正确打包进 APK"
                Log.e(TAG, "step2 FAIL: $msg")
                return null to msg
            }
            capturer = cap
            displaySurface = surface
            Log.i(TAG, "step2 OK: surface=$surface")

            // 步骤 3：Surface 序列化为 byte[]（跨进程通过 socket 传给 server）
            Log.i(TAG, "step3 Surface.writeToParcel ...")
            val surfaceBytes = try {
                val p = Parcel.obtain()
                try {
                    surface.writeToParcel(p, 0)
                    p.setDataPosition(0)
                    p.marshall()
                } finally {
                    p.recycle()
                }
            } catch (e: Exception) {
                val msg = "Surface 序列化失败：${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "step3 FAIL: $msg", e)
                cap.releaseNativeCapturer()
                capturer = null
                displaySurface = null
                return null to msg
            }
            Log.i(TAG, "step3 OK: surfaceBytes.size=${surfaceBytes.size}")

            // 步骤 4：创建 LocalServerSocket 等待 server 进程连入
            Log.i(TAG, "step4 new LocalServerSocket($SOCKET_NAME) ...")
            val localServerSocket = try {
                LocalServerSocket(SOCKET_NAME)
            } catch (e: IOException) {
                val msg = "LocalServerSocket 创建失败：${e.javaClass.simpleName}: ${e.message}" +
                        "（可能上次没释放，重启 App 试一下）"
                Log.e(TAG, "step4 FAIL: $msg", e)
                cap.releaseNativeCapturer()
                capturer = null
                displaySurface = null
                return null to msg
            }
            serverSocket = localServerSocket
            Log.i(TAG, "step4 OK: LocalServerSocket bound")

            // 步骤 5：推送 server APK + 启动 server 进程
            Log.i(TAG, "step5 ensureServerApk + launchServer ...")
            try {
                ShizukuProcessManager.ensureServerApk(context)
                serverProcess = ShizukuProcessManager.launchServer(SOCKET_NAME)
            } catch (e: Exception) {
                val msg = "server 进程启动失败：${e.message}"
                Log.e(TAG, "step5 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }

            // 异步读 server stdout/stderr 到 logcat（排查用）
            val (outT, errT) = ShizukuProcessManager.drainServerStdout(serverProcess!!) { line ->
                Log.i(TAG, line)
            }
            stdoutThread = outT
            stderrThread = errT

            // 步骤 6：等 server 进程连入 LocalServerSocket
            Log.i(TAG, "step6 serverSocket.accept() (timeout=${SERVER_CONNECT_TIMEOUT_MS}ms) ...")
            val client = try {
                acceptWithTimeout(localServerSocket, SERVER_CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                val msg = "等待 server 进程连入超时/失败：${e.javaClass.simpleName}: ${e.message}" +
                        "（server 启动可能崩溃，详见 [server.err] 日志）"
                Log.e(TAG, "step6 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }
            clientSocket = client
            Log.i(TAG, "step6 OK: server connected, client=$client")

            // 步骤 7：发 CREATE_VD
            Log.i(TAG, "step7 send MSG_CREATE_VD ...")
            val flags = DisplayManagerHelper.buildDisplayFlags()
            val request = VDRequest(
                width = width,
                height = height,
                density = DEFAULT_DPI,
                flags = flags,
                name = VIRTUAL_DISPLAY_NAME,
                surfaceBytes = surfaceBytes
            )
            try {
                VDProtocol.writeMessage(
                    client.outputStream,
                    VDProtocol.MSG_CREATE_VD,
                    request.toByteArray()
                )
            } catch (e: Exception) {
                val msg = "发 CREATE_VD 失败：${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "step7 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }

            // 步骤 8：读 CREATE_VD_RESP
            Log.i(TAG, "step8 read MSG_CREATE_VD_RESP (timeout=${CREATE_VD_RESP_TIMEOUT_MS}ms) ...")
            val resp = try {
                readCreateVdRespWithTimeout(client.inputStream, CREATE_VD_RESP_TIMEOUT_MS)
            } catch (e: Exception) {
                val msg = "读 CREATE_VD_RESP 超时/失败：${e.javaClass.simpleName}: ${e.message}" +
                        "（server 可能崩在 createVirtualDisplay，详见 [server.err] 日志）"
                Log.e(TAG, "step8 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }

            if (!resp.ok || resp.displayId <= 0) {
                val msg = "虚拟显示器创建失败：${resp.error.ifBlank { "未知错误 displayId=${resp.displayId}" }}"
                Log.e(TAG, "step8 FAIL: $msg")
                cleanupAll()
                return null to msg
            }

            cachedDisplayId = resp.displayId

            // 步骤 9：启动心跳保活线程
            keepAliveRunning.set(true)
            keepAliveThread = Thread({ runKeepAlive(client) }, "autobot-keepalive").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "✅ VirtualDisplay started: ${width}x${height} displayId=$cachedDisplayId")
            surface to ""
        } catch (e: Exception) {
            var cause: Throwable? = e
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            val detail = if (cause != null && cause !== e) {
                "（根因：${cause.javaClass.simpleName}: ${cause.message}）"
            } else ""
            val msg = "虚拟显示器启动异常: ${e.javaClass.simpleName}: ${e.message} $detail"
            Log.e(TAG, msg, e)
            cleanupAll()
            null to msg
        }
    }

    /**
     * 重启虚拟显示器并切换分辨率（横竖屏切换）
     */
    fun restartVirtualDisplay(newWidth: Int, newHeight: Int): Pair<Surface?, String> {
        if (newWidth == width && newHeight == height && cachedDisplayId > 0) {
            Log.i(TAG, "restartVirtualDisplay skipped: size unchanged")
            return displaySurface to ""
        }

        Log.i(TAG, "Restarting VirtualDisplay: ${width}x${height} -> ${newWidth}x${newHeight}")
        val existingPreviewSurface = cachedPreviewSurface
        stopVirtualDisplay()

        val (newSurface, err) = startVirtualDisplay(newWidth, newHeight)
        if (newSurface == null) {
            Log.e(TAG, "restartVirtualDisplay failed: $err")
            return null to err
        }

        if (existingPreviewSurface != null && existingPreviewSurface.isValid) {
            attachPreviewSurface(existingPreviewSurface)
            Log.i(TAG, "restartVirtualDisplay: re-attached preview surface")
        }

        return newSurface to ""
    }

    fun attachPreviewSurface(surface: Surface?) {
        cachedPreviewSurface = surface
        capturer?.setPreviewSurface(surface)
        Log.i(TAG, "Preview surface attached: $surface")
    }

    fun detachPreviewSurface() {
        cachedPreviewSurface = null
        capturer?.setPreviewSurface(null)
        Log.i(TAG, "Preview surface detached")
    }

    fun injectTouchDown(x: Int, y: Int) {
        ShellExecutor.execute("input tap $x $y", useShizuku = true, timeout = 2000)
    }

    fun injectTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        ShellExecutor.execute("input swipe $fromX $fromY $toX $toY 100", useShizuku = true, timeout = 2000)
    }

    fun injectTouchUp(x: Int, y: Int) {}

    fun getFrameBufferBitmap(): Bitmap? = capturer?.getFrameBufferBitmap()

    fun getFrameCount(): Long = capturer?.getFrameCount() ?: 0L

    /**
     * 停止虚拟显示器
     *
     * 流程：
     *   1. 停心跳线程
     *   2. 给 server 发 RELEASE_VD，等 RESP（超时 1s）
     *   3. 关 socket
     *   4. destroyForcibly server 进程
     *   5. 释放 NativeCapturer
     *   6. 重置 cachedDisplayId
     */
    fun stopVirtualDisplay() {
        Log.i(TAG, "stopVirtualDisplay: stopping...")

        // 1. 停心跳
        keepAliveRunning.set(false)
        keepAliveThread?.let { t ->
            t.interrupt()
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        keepAliveThread = null

        // 2. 给 server 发 RELEASE_VD
        val client = clientSocket
        if (client != null && client.isConnected) {
            try {
                VDProtocol.writeMessage(
                    client.outputStream,
                    VDProtocol.MSG_RELEASE_VD,
                    VDProtocol.EMPTY_PAYLOAD
                )
                // 等 RESP（最多 1s，server 收到 RELEASE 后会 release VD + exit）
                try {
                    val (type, _) = readWithTimeout(client.inputStream, 1_000)
                    Log.i(TAG, "Received msg type=$type from server (expected RELEASE_VD_RESP)")
                } catch (e: Exception) {
                    Log.w(TAG, "Wait RELEASE_VD_RESP timeout/exception (acceptable): ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Send RELEASE_VD failed: ${e.message}")
            }
        }

        // 3. 关 socket
        try { clientSocket?.close() } catch (e: Exception) { Log.w(TAG, "clientSocket close: ${e.message}") }
        clientSocket = null

        try { serverSocket?.close() } catch (e: Exception) { Log.w(TAG, "serverSocket close: ${e.message}") }
        serverSocket = null

        // 4. destroy server 进程
        ShizukuProcessManager.destroyServer(serverProcess)
        serverProcess = null

        // 等 stdout/stderr 线程自然退出（process 已死，readLine 返回 null）
        stdoutThread?.let { try { it.join(300) } catch (_: Exception) {} }; stdoutThread = null
        stderrThread?.let { try { it.join(300) } catch (_: Exception) {} }; stderrThread = null

        // 5. 释放 NativeCapturer
        try {
            capturer?.releaseNativeCapturer()
        } catch (e: Exception) {
            Log.e(TAG, "releaseNativeCapturer failed", e)
        }
        capturer = null
        displaySurface = null

        // 6. 重置 displayId
        cachedDisplayId = -1

        Log.i(TAG, "VirtualDisplay stopped")
    }

    // ============================== 内部实现 ==============================

    /**
     * 心跳保活主循环（App → Server）：
     *  - 每 5s 发 PING
     *  - 读 PONG；连续 PONG_TIMEOUT_MS 没收到 → 视为 server 死亡，本地 stopVirtualDisplay
     *  - 收到非 PONG 消息（如 server 主动断连前的错误）→ 记日志
     *  - socket 异常 → 本地 stopVirtualDisplay
     */
    private fun runKeepAlive(client: LocalSocket) {
        val out = client.outputStream
        val input = client.inputStream
        var lastPongTime = System.currentTimeMillis()

        while (keepAliveRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (!keepAliveRunning.get()) break

            // 发 PING
            try {
                VDProtocol.writeMessage(out, VDProtocol.MSG_PING, VDProtocol.EMPTY_PAYLOAD)
            } catch (e: Exception) {
                Log.w(TAG, "keepAlive: send PING failed: ${e.message}")
                break
            }

            // 读 PONG（短超时：1s，不阻塞主循环太久）
            try {
                val (type, _) = readWithTimeout(input, 1_000)
                when (type) {
                    VDProtocol.MSG_PONG -> {
                        lastPongTime = System.currentTimeMillis()
                    }
                    else -> {
                        Log.w(TAG, "keepAlive: unexpected msg type=$type during PING")
                    }
                }
            } catch (_: Exception) {
                // 1s 内没读到消息也正常（server 还没回 PONG），不报错
            }

            // 检查 PONG 超时
            if (System.currentTimeMillis() - lastPongTime > PONG_TIMEOUT_MS) {
                Log.e(TAG, "keepAlive: PONG timeout > ${PONG_TIMEOUT_MS}ms, server may be dead")
                break
            }
        }

        Log.w(TAG, "keepAlive thread exiting, will stopVirtualDisplay")
        keepAliveRunning.set(false)
        cachedDisplayId = -1  // 同步 UI 状态
        // 调用方应在 MonitorViewModel 的 StateFlow 监听中重新拉取
    }

    /**
     * 接受 server 进程的连接，带超时（LocalServerSocket.accept 本身是阻塞的，
     * 这里用一个工作线程 + join 实现"超时"语义）。
     */
    private fun acceptWithTimeout(server: LocalServerSocket, timeoutMs: Long): LocalSocket {
        val holder = arrayOfNulls<LocalSocket>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try {
                holder[0] = server.accept()
            } catch (e: Exception) {
                errHolder[0] = e
            }
        }, "autobot-accept").apply {
            isDaemon = true
            start()
        }
        t.join(timeoutMs)
        if (t.isAlive) {
            // accept 仍在阻塞，没有干净方式中断 Socket.accept。
            // 通过关闭 serverSocket 让 accept 抛 IOException 退出。
            try { server.close() } catch (_: Exception) {}
            t.interrupt()
            throw IOException("server.accept() timed out after ${timeoutMs}ms")
        }
        errHolder[0]?.let { throw it }
        return holder[0]
            ?: throw IOException("server.accept() returned null unexpectedly")
    }

    /**
     * 读 MSG_CREATE_VD_RESP，带超时
     */
    private fun readCreateVdRespWithTimeout(input: java.io.InputStream, timeoutMs: Long): VDResponse {
        val holder = arrayOfNulls<VDResponse>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try {
                val (type, payload) = VDProtocol.readMessage(input)
                if (type != VDProtocol.MSG_CREATE_VD_RESP) {
                    throw IOException("Expected CREATE_VD_RESP but got type=$type")
                }
                holder[0] = VDResponse.fromByteArray(payload)
            } catch (e: Exception) {
                errHolder[0] = e
            }
        }, "autobot-read-resp").apply {
            isDaemon = true
            start()
        }
        t.join(timeoutMs)
        if (t.isAlive) {
            t.interrupt()
            throw IOException("read CREATE_VD_RESP timed out after ${timeoutMs}ms")
        }
        errHolder[0]?.let { throw it }
        return holder[0]
            ?: throw IOException("read CREATE_VD_RESP returned null unexpectedly")
    }

    /**
     * 通用读消息，带超时
     */
    private fun readWithTimeout(input: java.io.InputStream, timeoutMs: Long): Pair<Int, ByteArray> {
        val holder = arrayOfNulls<Pair<Int, ByteArray>>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try {
                holder[0] = VDProtocol.readMessage(input)
            } catch (e: Exception) {
                errHolder[0] = e
            }
        }, "autobot-read-msg").apply {
            isDaemon = true
            start()
        }
        t.join(timeoutMs)
        if (t.isAlive) {
            t.interrupt()
            throw IOException("read timed out after ${timeoutMs}ms")
        }
        errHolder[0]?.let { throw it }
        return holder[0]
            ?: throw IOException("read returned null unexpectedly")
    }

    /**
     * 统一清理所有资源（启动失败时调用）
     */
    private fun cleanupAll() {
        // 停心跳
        keepAliveRunning.set(false)
        keepAliveThread?.let { t ->
            t.interrupt()
            try { t.join(300) } catch (_: Exception) {}
        }
        keepAliveThread = null

        // 关 socket
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        // 杀 server 进程
        ShizukuProcessManager.destroyServer(serverProcess)
        serverProcess = null

        // 停 stdout/stderr 线程
        stdoutThread?.let { try { it.join(300) } catch (_: Exception) {} }; stdoutThread = null
        stderrThread?.let { try { it.join(300) } catch (_: Exception) {} }; stderrThread = null

        // 释放 NativeCapturer
        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null
        displaySurface = null
        cachedDisplayId = -1
    }
}
