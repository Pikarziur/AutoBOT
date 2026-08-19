package com.autobot.app.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.ShizukuProcessManager
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.server.FramePacket
import com.autobot.app.server.TouchEvent
import com.autobot.app.server.VDProtocol
import com.autobot.app.server.VDRequest
import com.autobot.app.server.VDResponse
import com.autobot.app.third.DisplayManagerHelper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟显示器合成服务（scrcpy/MAA-Meow 同款数据流架构）
 *
 * ★关键架构（修复 LocalSocket Permission denied）★：
 *   不再使用 LocalServerSocket/LocalSocket abstract namespace。
 *   Android 10+ SELinux 禁止 shell domain 连接 untrusted_app domain 的 abstract socket。
 *   改用 Shizuku.newProcess 返回的 Process 的 stdin/stdout pipe 跨进程通信。
 *
 * ┌─ App 进程（本类）─────────────────────────────────────────────┐
 * │  MonitorViewModel → CompositionService                        │
 * │                                                             │
 * │  step1: Shizuku 授权检查                                      │
 * │  step2: NativeCapturer.prepareFrameBuffer                     │
 * │  step3: ShizukuProcessManager.ensureServerApk                 │
 * │  step4: launchServer() → serverProcess: Process               │
 * │         ↳ stderrDrainThread 持续读 process.errorStream        │
 * │           → Log.i("ServerMain.stderr", line) 写到 logcat     │
 * │  step5: writeMessage(process.outputStream, MSG_CREATE_VD)    │
 * │  step6: readMessage(process.inputStream) → MSG_CREATE_VD_RESP│
 * │         → cachedDisplayId                                    │
 * │  step7: keepAliveThread 循环 write PING (process.outputStream)│
 * │  step8: frameReceiveThread 循环 readMessage(process.inputStream)
 * │         → MSG_FRAME → FramePacket.decodeBitmap                │
 * │         → NativeCapturer.injectExternalFrame(bitmap)          │
 * └───────────────────────────────────────────────────────────────┘
 *          ▲                                           │
 *          │ stdin/stdout pipe + VDProtocol 二进制帧   │
 *          ▼                                           │
 * ┌─ Server 进程（shell uid，ServerMain.main）───────────────────┐
 * │  step1: System.in.read → MSG_CREATE_VD                         │
 * │  step2: Workarounds.apply + FakeContext                        │
 * │  step3: 反射 DisplayManager(Context)                            │
 * │  step4: ImageReader.newInstance(w,h,YUV_420_888,3)             │
 * │  step5: createVirtualDisplay(name,w,h,dpi,reader.surface,f)    │
 * │  step6: ImageReader.onImageAvailable → acquireLatestImage       │
 * │         → YUV planes → compress JPEG → FramePacket             │
 * │         → writeMessage(System.out, MSG_FRAME)                 │
 * │  step7: System.in 循环 read → PING/PONG / RELEASE_VD            │
 * └───────────────────────────────────────────────────────────────┘
 *
 * 关键修复历史：
 *  1. Surface.writeToParcel + marshall() 失败（含 Binder）→ 改用 server 端 JPEG 传输
 *  2. LocalSocket Permission denied（SELinux）→ 改用 stdin/stdout pipe
 *
 * 公共 API 保持与 MonitorViewModel 完全兼容：
 *  startVirtualDisplay / restartVirtualDisplay / displayId / isLandscape /
 *  attachPreviewSurface / detachPreviewSurface / injectTouchDown/Move/Up /
 *  getFrameBufferBitmap / getFrameCount / stopVirtualDisplay
 */
class CompositionService(private val context: Context) {

    companion object {
        private const val TAG = "CompositionSvc"

        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 960
        const val DEFAULT_DPI = 240
        private const val VIRTUAL_DISPLAY_NAME = "AutoBOT-VirtualDisplay"
        private const val PING_INTERVAL_MS = 5_000L
        private const val SERVER_HANDSHAKE_TIMEOUT_MS = 8_000L
        private const val CREATE_VD_RESP_TIMEOUT_MS = 15_000L
        /** JPEG 压缩质量 1~100：90 足够清晰识别；100 帧变大延迟高 */
        private const val JPEG_QUALITY = 90
        /** 30fps 是 VD 默认刷新率，识别/点击场景 15fps 也够用 */
        private const val MAX_FPS = 30
    }

    /**
     * NativeCapturer 在 App 进程仅作为"帧消费者"：
     *   - prepareFrameBuffer(w,h) 分配帧缓冲内存
     *   - injectExternalFrame(Bitmap) 把 server 发来的 JPEG 解码后帧写入 frameBuffer
     *   - setPreviewSurface() 控制预览 blit 目标
     *   - getFrameBufferBitmap() / getFrameCount() 从 Native 端读
     */
    private var capturer: NativeCapturer? = null

    private var cachedPreviewSurface: android.view.Surface? = null

    /** Shizuku.newProcess 返回的 Process 对象，所有跨进程通信通过它的 3 个流 */
    @Volatile
    private var serverProcess: Process? = null

    /** 持续读 server 的 stderr（避免 server 写满 stderr 后阻塞）+ 转发到 logcat */
    @Volatile
    private var stderrDrainThread: Thread? = null

    /** PING 线程（只写不发读，避免与 frameReceiveThread 抢 inputStream） */
    @Volatile
    private var keepAliveThread: Thread? = null
    private val keepAliveRunning = AtomicBoolean(false)

    /** MSG_FRAME 接收线程（独占 process.inputStream） */
    @Volatile
    private var frameReceiveThread: Thread? = null
    private val frameReceiveRunning = AtomicBoolean(false)

    @Volatile
    private var cachedDisplayId: Int = -1

    var width: Int = DEFAULT_WIDTH
        private set
    var height: Int = DEFAULT_HEIGHT
        private set

    val isLandscape: Boolean get() = width > height
    val displayId: Int get() = cachedDisplayId

    fun startVirtualDisplay(width: Int = DEFAULT_WIDTH,
                            height: Int = DEFAULT_HEIGHT): Pair<android.view.Surface?, String> {
        if (cachedDisplayId > 0) {
            Log.w(TAG, "VirtualDisplay already running, stop first")
            return null to ""
        }

        // 防护：确保旧的 native capturer 已释放（防止 stopVirtualDisplay 未完全清理的竞态）
        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null

        // step1: Shizuku 检查
        val diag = ShizukuManager.diagnoseShizuku(context)
        when (diag) {
            ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED -> {
                val msg = "Shizuku 未安装：请先安装 Shizuku App（rikka.shizuku / moe.shizuku.privileged.api）"
                Log.e(TAG, "step1 FAIL: $msg"); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> {
                val msg = ("Shizuku 未连接：请打开 Shizuku App 并通过「无线调试」或「ADB 命令」启动服务。" +
                        "注意：Root 模式启动 Shizuku 在 Android 12+ 会触发 SecurityException " +
                        "\"packageName must match the calling uid\"，必须用 ADB/无线调试 模式启动。")
                Log.e(TAG, "step1 FAIL: $msg"); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> {
                val msg = "Shizuku 已连接但未授权：请在设置页面点「授权 Shizuku」按钮"
                Log.e(TAG, "step1 FAIL: $msg"); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR -> {
                val msg = "Shizuku 状态异常：请重启 Shizuku 服务后重试"
                Log.e(TAG, "step1 FAIL: $msg"); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.OK -> {
                Log.i(TAG, "step1 Shizuku OK")
            }
        }

        this.width = width
        this.height = height

        return try {
            // step2: 初始化 NativeCapturer（仅分配 frameBuffer + 准备 preview blit）
            Log.i(TAG, "step2 NativeCapturer prepare ...")
            val cap = NativeCapturer()
            val prepared = try {
                cap.prepareFrameBuffer(width, height)
            } catch (_: Throwable) {
                val s = cap.setupNativeCapturer(width, height)
                try { s?.release() } catch (_: Exception) {}
                true
            }
            if (!prepared) {
                val msg = "NativeCapturer.prepareFrameBuffer 失败（分配 $width x $height 帧缓冲内存出错）"
                Log.e(TAG, "step2 FAIL: $msg")
                return null to msg
            }
            capturer = cap
            Log.i(TAG, "step2 OK: NativeCapturer frame buffer ready")
            if (cachedPreviewSurface != null && cachedPreviewSurface!!.isValid) {
                cap.setPreviewSurface(cachedPreviewSurface)
            }

            // ★不再创建 LocalServerSocket★（step3 直接启动 server 进程）
            Log.i(TAG, "step3 ensureServerApk + launchServer ...")
            try {
                ShizukuProcessManager.ensureServerApk(context)
                serverProcess = ShizukuProcessManager.launchServer()
            } catch (e: Exception) {
                val msg = "server 进程启动失败：${e.message}"
                Log.e(TAG, "step3 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }

            // step3.5: 立即启动 stderr drain 线程
            // app_process 启动时会输出 AndroidRuntime 日志到 stderr，不 drain 会阻塞 server 写
            stderrDrainThread = Thread({
                try {
                    BufferedReader(InputStreamReader(serverProcess!!.errorStream)).use { reader ->
                        reader.forEachLine { line ->
                            Log.i("ServerMain.stderr", line)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stderr drain ended: ${e.message}")
                }
            }, "autobot-server-stderr").apply { isDaemon = true; start() }

            // step4: 发 CREATE_VD（注意！不再带 Surface Parcel，server 自己创建 ImageReader）
            Log.i(TAG, "step4 send MSG_CREATE_VD ...")
            val flags = DisplayManagerHelper.buildDisplayFlags()
            val request = VDRequest(
                width = width,
                height = height,
                density = DEFAULT_DPI,
                flags = flags,
                name = VIRTUAL_DISPLAY_NAME,
                jpegQuality = JPEG_QUALITY,
                maxFps = MAX_FPS
            )
            try {
                VDProtocol.writeMessage(serverProcess!!.outputStream,
                    VDProtocol.MSG_CREATE_VD, request.toByteArray())
            } catch (e: Exception) {
                val msg = "发 CREATE_VD 失败：${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "step4 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }

            // step5: 读 CREATE_VD_RESP（带超时）
            // 注意：server 进程刚启动，AndroidRuntime 初始化需要 1~2s，
            // 然后 ServerMain.main() 才开始读 stdin → 处理 → 写 MSG_CREATE_VD_RESP。
            // 超时给 15s 足够覆盖 MIUI/HyperOS 上 app_process 冷启动。
            Log.i(TAG, "step5 read MSG_CREATE_VD_RESP (timeout=${CREATE_VD_RESP_TIMEOUT_MS}ms) ...")
            val resp = try {
                readCreateVdRespWithTimeout(serverProcess!!.inputStream, CREATE_VD_RESP_TIMEOUT_MS)
            } catch (e: Exception) {
                val msg = "读 CREATE_VD_RESP 失败：${e.javaClass.simpleName}: ${e.message}" +
                        "（详见 logcat [ServerMain.stderr] / [ServerMain] 标签）"
                Log.e(TAG, "step5 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }
            if (!resp.ok || resp.displayId <= 0) {
                val msg = "虚拟显示器创建失败：${resp.error.ifBlank { "displayId=${resp.displayId}" }}"
                Log.e(TAG, "step5 FAIL: $msg")
                cleanupAll()
                return null to msg
            }
            cachedDisplayId = resp.displayId

            // step6: 启动 PING 线程（保活，只写不读）
            keepAliveRunning.set(true)
            keepAliveThread = Thread({ runKeepAlive(serverProcess!!) }, "autobot-keepalive").apply {
                isDaemon = true; start()
            }

            // step7: 启动 MSG_FRAME 接收线程 → 解码 JPEG → 注入到 NativeCapturer
            frameReceiveRunning.set(true)
            frameReceiveThread = Thread({ runFrameReceiver(serverProcess!!) }, "autobot-frame-receiver").apply {
                isDaemon = true; start()
            }

            Log.i(TAG, "✅ VirtualDisplay started: ${width}x${height} displayId=$cachedDisplayId")
            null to ""
        } catch (e: Exception) {
            var cause: Throwable? = e
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            val detail = if (cause != null && cause !== e) "（根因：${cause.javaClass.simpleName}: ${cause.message}）" else ""
            val msg = "启动异常：${e.javaClass.simpleName}: ${e.message} $detail"
            Log.e(TAG, msg, e)
            cleanupAll()
            null to msg
        }
    }

    fun restartVirtualDisplay(newWidth: Int, newHeight: Int): Pair<android.view.Surface?, String> {
        if (newWidth == width && newHeight == height && cachedDisplayId > 0) {
            Log.i(TAG, "restart skipped: size unchanged")
            return null to ""
        }
        Log.i(TAG, "Restart: ${width}x${height} -> ${newWidth}x${newHeight}")
        val existingPreview = cachedPreviewSurface
        stopVirtualDisplay()
        val (_, err) = startVirtualDisplay(newWidth, newHeight)
        if (err.isNotEmpty()) return null to err
        if (existingPreview != null && existingPreview.isValid) attachPreviewSurface(existingPreview)
        return null to ""
    }

    fun attachPreviewSurface(surface: android.view.Surface?) {
        cachedPreviewSurface = surface
        capturer?.setPreviewSurface(surface)
        Log.i(TAG, "Preview surface attached: $surface")
    }

    fun detachPreviewSurface() {
        cachedPreviewSurface = null
        capturer?.setPreviewSurface(null)
    }

    fun injectTouchDown(x: Int, y: Int) {
        val proc = serverProcess ?: return
        try {
            VDProtocol.writeMessage(proc.outputStream, VDProtocol.MSG_TOUCH_DOWN,
                TouchEvent(x, y).toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "injectTouchDown: write failed: ${e.message}")
        }
    }

    fun injectTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        // MotionEvent 注入只需要当前坐标，fromX/fromY 不需要
        val proc = serverProcess ?: return
        try {
            VDProtocol.writeMessage(proc.outputStream, VDProtocol.MSG_TOUCH_MOVE,
                TouchEvent(toX, toY).toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "injectTouchMove: write failed: ${e.message}")
        }
    }

    fun injectTouchUp(x: Int, y: Int) {
        val proc = serverProcess ?: return
        try {
            VDProtocol.writeMessage(proc.outputStream, VDProtocol.MSG_TOUCH_UP,
                TouchEvent(x, y).toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "injectTouchUp: write failed: ${e.message}")
        }
    }

    fun getFrameBufferBitmap(): Bitmap? = capturer?.getFrameBufferBitmap()
    fun getFrameCount(): Long = capturer?.getFrameCount() ?: 0L

    fun stopVirtualDisplay() {
        Log.i(TAG, "stopping ...")
        // 停 PING
        keepAliveRunning.set(false)
        keepAliveThread?.let { t -> t.interrupt(); try { t.join(500) } catch (_: Exception) {} }; keepAliveThread = null

        // 停帧接收
        frameReceiveRunning.set(false)
        frameReceiveThread?.let { t -> t.interrupt(); try { t.join(500) } catch (_: Exception) {} }; frameReceiveThread = null

        // 发 RELEASE_VD 让 server 优雅退出
        val proc = serverProcess
        if (proc != null) {
            try {
                VDProtocol.writeMessage(proc.outputStream, VDProtocol.MSG_RELEASE_VD, VDProtocol.EMPTY_PAYLOAD)
                try {
                    readWithTimeout(proc.inputStream, 1_000)
                    Log.i(TAG, "Got RELEASE_VD response")
                } catch (e: Exception) {
                    Log.w(TAG, "Wait RELEASE_VD_RESP timeout: ${e.message}（可接受，将强制 destroy）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Send RELEASE_VD failed: ${e.message}")
            }
        }

        // 销毁 server 进程（stdin/stdout pipe 由 destroy 自动关闭）
        ShizukuProcessManager.destroyServer(serverProcess); serverProcess = null
        stderrDrainThread?.let { try { it.join(500) } catch (_: Exception) {} }; stderrDrainThread = null

        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null
        cachedDisplayId = -1
        Log.i(TAG, "VirtualDisplay stopped")
    }

    // ===================== 内部实现 =====================

    /**
     * MSG_FRAME 接收主循环（独占 process.inputStream）：
     *  - VDProtocol.readMessage 拿到新消息 → 处理 MSG_FRAME / MSG_PONG / 其他忽略
     *  - FramePacket.fromByteArray → decodeBitmap() → NativeCapturer.injectExternalFrame(bitmap)
     *    → Native 端自动写入 frameBuffer + 若 previewSurface 已设置则 blit 到 SurfaceView
     *  - pipe 异常：退出循环 + cachedDisplayId=-1（UI 可感知）
     */
    private fun runFrameReceiver(process: Process) {
        val input = process.inputStream
        Log.i(TAG, "frameReceiveThread started")
        while (frameReceiveRunning.get() && !Thread.currentThread().isInterrupted) {
            val (type, payload) = try {
                VDProtocol.readMessage(input)
            } catch (e: Exception) {
                Log.w(TAG, "frameReceive read failed: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            when (type) {
                VDProtocol.MSG_FRAME -> {
                    try {
                        val pkt = FramePacket.fromByteArray(payload)
                        val bitmap = pkt.decodeBitmap() ?: continue
                        capturer?.injectExternalFrame(bitmap)
                        // injectExternalFrame 内部已自增 frameCount
                    } catch (e: Exception) {
                        Log.e(TAG, "FramePacket decode failed: ${e.message}")
                    }
                    // 写 MSG_FRAME_ACK 让 server 节流（避免发送过快导致 pipe 缓冲积压）
                    try {
                        VDProtocol.writeMessage(process.outputStream,
                            VDProtocol.MSG_FRAME_ACK, VDProtocol.EMPTY_PAYLOAD)
                    } catch (_: Exception) {}
                }
                VDProtocol.MSG_PONG -> {
                    // 收到 PONG 表明 pipe 还活着；不做事
                }
                VDProtocol.MSG_RELEASE_VD_RESP -> {
                    // 收到 server 释放响应：可以退出了
                    Log.i(TAG, "Got RELEASE_VD_RESP from server, exiting frameReceive loop")
                    break
                }
            }
        }
        frameReceiveRunning.set(false)
        cachedDisplayId = -1
        Log.i(TAG, "frameReceiveThread exited")
    }

    /**
     * 保活线程：**只负责定时写 PING**，**不读**（防止和 frameReceiveThread 抢 inputStream）。
     * pipe 异常会通过 writeMessage 抛 IOException，本线程捕获后 break。
     * 真正的"是否还活着"判定：frameReceiveThread 持续读 MSG_FRAME，
     * 一旦 pipe 断开 readMessage 抛异常 break，触发 cleanup。
     */
    private fun runKeepAlive(process: Process) {
        val out = process.outputStream
        Log.i(TAG, "keepAlive started (write-only PING every ${PING_INTERVAL_MS}ms)")
        while (keepAliveRunning.get() && !Thread.currentThread().isInterrupted) {
            try { Thread.sleep(PING_INTERVAL_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
            if (!keepAliveRunning.get()) break
            try {
                VDProtocol.writeMessage(out, VDProtocol.MSG_PING, VDProtocol.EMPTY_PAYLOAD)
            } catch (e: Exception) {
                Log.w(TAG, "keepAlive send PING failed: ${e.message}")
                break
            }
        }
        keepAliveRunning.set(false)
        Log.i(TAG, "keepAlive thread exited")
    }

    private fun readCreateVdRespWithTimeout(input: java.io.InputStream, timeoutMs: Long): VDResponse {
        val holder = arrayOfNulls<VDResponse>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try {
                val (type, payload) = VDProtocol.readMessage(input)
                if (type != VDProtocol.MSG_CREATE_VD_RESP) {
                    throw IOException("expect CREATE_VD_RESP, got $type")
                }
                holder[0] = VDResponse.fromByteArray(payload)
            } catch (e: Exception) { errHolder[0] = e }
        }, "autobot-read-resp").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) {
            t.interrupt()
            throw IOException("read CREATE_VD_RESP timed out after ${timeoutMs}ms" +
                    "（server 进程可能未启动成功，详见 [ServerMain.stderr] / [ServerMain] logcat）")
        }
        errHolder[0]?.let { throw it }
        return holder[0] ?: throw IOException("read resp returned null")
    }

    private fun readWithTimeout(input: java.io.InputStream, timeoutMs: Long): Pair<Int, ByteArray> {
        val holder = arrayOfNulls<Pair<Int, ByteArray>>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try { holder[0] = VDProtocol.readMessage(input) } catch (e: Exception) { errHolder[0] = e }
        }, "autobot-read-msg").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) { t.interrupt(); throw IOException("read timed out after ${timeoutMs}ms") }
        errHolder[0]?.let { throw it }
        return holder[0] ?: throw IOException("read returned null")
    }

    private fun cleanupAll() {
        keepAliveRunning.set(false); frameReceiveRunning.set(false)
        keepAliveThread?.let { it.interrupt(); try { it.join(300) } catch (_: Exception) {} }; keepAliveThread = null
        frameReceiveThread?.let { it.interrupt(); try { it.join(300) } catch (_: Exception) {} }; frameReceiveThread = null
        ShizukuProcessManager.destroyServer(serverProcess); serverProcess = null
        stderrDrainThread?.let { try { it.join(300) } catch (_: Exception) {} }; stderrDrainThread = null
        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null
        cachedDisplayId = -1
    }
}
