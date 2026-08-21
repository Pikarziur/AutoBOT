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
import com.autobot.app.ui.settings.VdResolutionMode
import com.autobot.app.util.BitmapPool
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟显示器合成服务
 *
 * 关键架构（修复 LocalSocket Permission denied）：使用 Shizuku.newProcess 返回的 Process 的 stdin/stdout pipe
 * 跨进程通信（Android 10+ SELinux 禁止 shell domain 连接 untrusted_app domain 的 abstract socket）。
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

        /**
         * 分辨率预设（默认 1080P，可选 720P 省内存档）
         * 保证"设置页 → MonitorViewModel → ShellExecutor"都读到同一组值。
         */
        @Deprecated("请改用 VdResolutionMode.resolveCurrent(context) 获取当前生效档位")
        const val DEFAULT_WIDTH = 540
        @Deprecated("请改用 VdResolutionMode.resolveCurrent(context) 获取当前生效档位")
        const val DEFAULT_HEIGHT = 960
        @Deprecated("请改用 VdResolutionMode.resolveCurrent(context).dpi")
        const val DEFAULT_DPI = 240

        @JvmStatic
        fun resolveMode(ctx: Context): VdResolutionMode = VdResolutionMode.readFromPrefs(ctx)

        private const val VIRTUAL_DISPLAY_NAME = "AutoBOT-VirtualDisplay"
        private const val PING_INTERVAL_MS = 5_000L
        private const val SERVER_HANDSHAKE_TIMEOUT_MS = 8_000L
        private const val CREATE_VD_RESP_TIMEOUT_MS = 15_000L
        /** JPEG 压缩质量：90 足够清晰识别；100 帧变大延迟高 */
        private const val JPEG_QUALITY = 90
        /**
         * 帧率上限：CPU 优化降为 15fps（识别/点击场景足够，省 50% 帧处理 CPU）。
         * 30fps 时每秒 30 次 JPEG 编解码 + Bitmap 分配，识别场景实际只 2fps 消费，浪费严重。
         */
        private const val MAX_FPS = 15
        /**
         * 帧格式：1=RGBA 直传（CPU 优化，跳过 JPEG 编解码），0=JPEG 兼容。
         * RGBA 直传下 pipe 带宽约 60MB/s（1080P 15fps），现代 Android 设备 stdin/stdout pipe 可承载。
         * 若低端设备 pipe 阻塞严重，可改回 0 走 JPEG。
         */
        private const val FRAME_FORMAT = 1
    }

    private var capturer: NativeCapturer? = null

    private var cachedPreviewSurface: android.view.Surface? = null

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var stderrDrainThread: Thread? = null

    /** PING 线程（只写不发读，避免与 frameReceiveThread 抢 inputStream） */
    @Volatile
    private var keepAliveThread: Thread? = null
    private val keepAliveRunning = AtomicBoolean(false)

    @Volatile
    private var frameReceiveThread: Thread? = null
    private val frameReceiveRunning = AtomicBoolean(false)

    @Volatile
    private var cachedDisplayId: Int = -1

    /** 当前 VD 帧格式：1=RGBA 直传，0=JPEG；runFrameReceiver 据此路由解码路径 */
    @Volatile
    private var frameFormat: Int = FRAME_FORMAT

    private var currentMode: VdResolutionMode = resolveMode(context)

    var width: Int = currentMode.width
        private set
    var height: Int = currentMode.height
        private set
    var densityDpi: Int = currentMode.dpi
        private set

    val isLandscape: Boolean get() = width > height
    val displayId: Int get() = cachedDisplayId
    val vdMode: VdResolutionMode get() = currentMode

    fun startVirtualDisplay(): Pair<android.view.Surface?, String> {
        val mode = resolveMode(context)
        currentMode = mode
        return startVirtualDisplay(mode.width, mode.height, mode.dpi)
    }

    /**
     * 兼容老调用：显式指定宽高时，DPI 用档位默认（= resolveMode(context).dpi），
     * 避免新设置下 density 不匹配导致 VD 里 UI 大小异常。
     */
    fun startVirtualDisplay(width: Int, height: Int): Pair<android.view.Surface?, String> {
        val mode = resolveMode(context)
        return startVirtualDisplay(width, height, mode.dpi)
    }

    fun startVirtualDisplay(width: Int,
                            height: Int,
                            densityDpi: Int): Pair<android.view.Surface?, String> {
        if (cachedDisplayId > 0) {
            Log.w(TAG, "VirtualDisplay already running, stop first")
            return null to ""
        }

        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null

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
        this.densityDpi = densityDpi

        return try {
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

            Log.i(TAG, "step4 send MSG_CREATE_VD mode=${currentMode.name} " +
                    "(${width}x${height}@${densityDpi}dpi) ...")
            val flags = DisplayManagerHelper.buildDisplayFlags()
            val request = VDRequest(
                width = width,
                height = height,
                density = densityDpi,        // 720P → 320, 1080P → 420，档位配套密度
                flags = flags,
                name = VIRTUAL_DISPLAY_NAME,
                jpegQuality = JPEG_QUALITY,
                maxFps = MAX_FPS,
                frameFormat = FRAME_FORMAT
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

            // server 进程刚启动 AndroidRuntime 初始化需要 1~2s，超时给 15s 覆盖 MIUI/HyperOS 上 app_process 冷启动
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
            // 记录请求的 frameFormat，runFrameReceiver 据此路由
            frameFormat = request.frameFormat

            keepAliveRunning.set(true)
            keepAliveThread = Thread({ runKeepAlive(serverProcess!!) }, "autobot-keepalive").apply {
                isDaemon = true; start()
            }

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

    fun injectBack() {
        val proc = serverProcess ?: return
        try {
            VDProtocol.writeMessage(proc.outputStream, VDProtocol.MSG_KEY_BACK,
                VDProtocol.EMPTY_PAYLOAD)
        } catch (e: Exception) {
            Log.w(TAG, "injectBack: write failed: ${e.message}")
        }
    }

    fun getFrameBufferBitmap(): Bitmap? = capturer?.getFrameBufferBitmap()
    fun getFrameCount(): Long = capturer?.getFrameCount() ?: 0L

    fun stopVirtualDisplay() {
        Log.i(TAG, "stopping ...")
        keepAliveRunning.set(false)
        keepAliveThread?.let { t -> t.interrupt(); try { t.join(500) } catch (_: Exception) {} }; keepAliveThread = null

        frameReceiveRunning.set(false)
        frameReceiveThread?.let { t -> t.interrupt(); try { t.join(500) } catch (_: Exception) {} }; frameReceiveThread = null

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

        ShizukuProcessManager.destroyServer(serverProcess); serverProcess = null
        stderrDrainThread?.let { try { it.join(500) } catch (_: Exception) {} }; stderrDrainThread = null

        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null
        cachedDisplayId = -1
        // VD 停止时清空 Bitmap 池，释放 native 像素内存
        BitmapPool.clear()
        Log.i(TAG, "VirtualDisplay stopped")
    }

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
                        if (frameFormat == 1) {
                            // CPU 优化路径：RGBA 直传，跳过 JPEG 解码 + Bitmap 分配
                            // pkt.jpegBytes 在 RGBA 模式下实为 RGBA 原始字节
                            capturer?.injectExternalFrameRaw(pkt.jpegBytes, pkt.width, pkt.height)
                        } else {
                            // JPEG 兼容路径：解码 + 归池
                            val decoded = pkt.decodeBitmap()
                            if (decoded != null) {
                                try {
                                    capturer?.injectExternalFrame(decoded)
                                } finally {
                                    BitmapPool.release(decoded)
                                }
                            }
                        }
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
                }
                VDProtocol.MSG_RELEASE_VD_RESP -> {
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
        // VD 异常停止时同样清空 Bitmap 池
        BitmapPool.clear()
    }
}
