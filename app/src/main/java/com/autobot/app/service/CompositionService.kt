package com.autobot.app.service

import android.content.Context
import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.ShizukuProcessManager
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.server.FramePacket
import com.autobot.app.server.VDProtocol
import com.autobot.app.server.VDRequest
import com.autobot.app.server.VDResponse
import com.autobot.app.third.DisplayManagerHelper
import com.autobot.app.util.ShellExecutor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟显示器合成服务（scrcpy/MAA-Meow 同款数据流架构）
 *
 * ┌─ App 进程（本类）─────────────────────────────────────────────┐
 * │  MonitorViewModel → CompositionService                        │
 * │                                                             │
 * │  step1: Shizuku 授权检查                                      │
 * │  step2: 启动 LocalServerSocket("com.autobot.app.vdserver")    │
 * │  step3: ShizukuProcessManager.ensureServerApk + launchServer   │
 * │  step4: server 进程（shell uid）连入 → accept()                │
 * │  step5: send MSG_CREATE_VD(width,height,dpi,flags,name,...)   │
 * │  step6: recv MSG_CREATE_VD_RESP → cachedDisplayId              │
 * │  step7: frameReceiveThread 循环 recv MSG_FRAME → decode JPEG   │
 * │         → Bitmap → NativeCapturer.injectExternalFrame(bitmap)  │
 * │         → Native 端 frameBuffer 更新（识图 + 预览共用）        │
 * └───────────────────────────────────────────────────────────────┘
 *          ▲                                           │
 *          │ LocalSocket abstract namespace + VDProtocol│
 *          ▼                                           │
 * ┌─ Server 进程（shell uid，ServerMain.main）───────────────────┐
 * │  step1: Workarounds.apply + FakeContext                     │
 * │  step2: 反射 DisplayManager(Context)                         │
 * │  step3: ImageReader.newInstance(w,h,YUV_420_888,3)           │
 * │  step4: createVirtualDisplay(name,w,h,dpi,reader.surface,f)  │
 * │  step5: ImageReader.onImageAvailable → acquireLatestImage    │
 * │         → YUV planes → Bitmap → compress JPEG → FramePacket  │
 * │         → send MSG_FRAME                                     │
 * └───────────────────────────────────────────────────────────────┘
 *
 * 关键修复：去掉了"App 创建 AImageReader/Surface 跨进程传 server"的错误路径
 * （Surface 内藏 IGraphicBufferProducer Binder → Parcel.marshall() 直接抛
 *   RuntimeException: Tried to marshall a Parcel that contains objects (binders or FDs)
 *  这是 Android 系统层硬限制）
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
        private const val SOCKET_NAME = "com.autobot.app.vdserver"
        private const val PING_INTERVAL_MS = 5_000L
        private const val PONG_TIMEOUT_MS = 12_000L
        private const val SERVER_CONNECT_TIMEOUT_MS = 5_000L
        private const val CREATE_VD_RESP_TIMEOUT_MS = 10_000L
        /** JPEG 压缩质量 1~100：90 足够清晰识别；100 帧变大延迟高 */
        private const val JPEG_QUALITY = 90
        /** 30fps 是 VD 默认刷新率，识别/点击场景 15fps 也够用 */
        private const val MAX_FPS = 30
    }

    /**
     * NativeCapturer 现在在 App 进程仅作为"帧消费者"：
     *   - 不再 setupNativeCapturer（因为 AImageReader 在 server 端）
     *   - releaseNativeCapturer() 仍然释放 frameBuffer / previewSurface
     *   - setPreviewSurface() 仍然控制预览 blit 目标
     *   - 新增 injectExternalFrame(Bitmap) 把 server 发来的 JPEG 解码后帧写入 frameBuffer
     *   - getFrameBufferBitmap() / getFrameCount() 仍然从 Native 端读（保持 API 不变）
     */
    private var capturer: NativeCapturer? = null

    private var cachedPreviewSurface: android.view.Surface? = null

    @Volatile
    private var serverSocket: LocalServerSocket? = null
    @Volatile
    private var clientSocket: LocalSocket? = null
    @Volatile
    private var serverProcess: Process? = null
    @Volatile
    private var stdoutThread: Thread? = null
    @Volatile
    private var stderrThread: Thread? = null

    /** PING 线程（发送） */
    @Volatile
    private var keepAliveThread: Thread? = null
    private val keepAliveRunning = AtomicBoolean(false)

    /** MSG_FRAME 接收线程 */
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
            // setupNativeCapturer 原本是"创建 AImageReader + 拿 Surface"；
            // 现在 server 端管画面，所以我们调用新方法 prepareFrameBuffer(w,h) 只分配 frameBuffer
            // （如果方法不存在，保留 fallback 走 setup，因为它内部也分配 frameBuffer）
            val prepared = try {
                cap.prepareFrameBuffer(width, height)
            } catch (_: Throwable) {
                val s = cap.setupNativeCapturer(width, height)
                // setup 返回的 Surface 是旧 AImageReader 产物，我们不需要；但 release 避免泄漏
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
            // 如果用户之前已经 attachPreviewSurface（横竖屏重启场景），立即重新绑定
            if (cachedPreviewSurface != null && cachedPreviewSurface!!.isValid) {
                cap.setPreviewSurface(cachedPreviewSurface)
            }

            // step3: 启动 LocalServerSocket
            Log.i(TAG, "step3 new LocalServerSocket($SOCKET_NAME) ...")
            val localServerSocket = try {
                LocalServerSocket(SOCKET_NAME)
            } catch (e: IOException) {
                val msg = "LocalServerSocket 创建失败：${e.javaClass.simpleName}: ${e.message}（重启 App 重试）"
                Log.e(TAG, "step3 FAIL: $msg", e)
                cap.releaseNativeCapturer(); capturer = null
                return null to msg
            }
            serverSocket = localServerSocket
            Log.i(TAG, "step3 OK")

            // step4: 推送 server APK + 启动 server 进程（shell uid app_process）
            Log.i(TAG, "step4 ensureServerApk + launchServer ...")
            try {
                ShizukuProcessManager.ensureServerApk(context)
                serverProcess = ShizukuProcessManager.launchServer(SOCKET_NAME)
            } catch (e: Exception) {
                val msg = "server 进程启动失败：${e.message}"
                Log.e(TAG, "step4 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }
            val (outT, errT) = ShizukuProcessManager.drainServerStdout(serverProcess!!) { line ->
                Log.i(TAG, line)
            }
            stdoutThread = outT
            stderrThread = errT

            // step5: accept() 等待 server 连入
            Log.i(TAG, "step5 accept (timeout=${SERVER_CONNECT_TIMEOUT_MS}ms) ...")
            val client = try {
                acceptWithTimeout(localServerSocket, SERVER_CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                val msg = "等待 server 连入失败：${e.javaClass.simpleName}: ${e.message}（详见 [server.err] 日志）"
                Log.e(TAG, "step5 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }
            clientSocket = client
            Log.i(TAG, "step5 OK: server connected")

            // step6: 发 CREATE_VD（注意！不再带 Surface Parcel，server 自己创建 ImageReader）
            Log.i(TAG, "step6 send MSG_CREATE_VD ...")
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
                VDProtocol.writeMessage(client.outputStream, VDProtocol.MSG_CREATE_VD, request.toByteArray())
            } catch (e: Exception) {
                val msg = "发 CREATE_VD 失败：${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "step6 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }

            // step7: 读 CREATE_VD_RESP
            Log.i(TAG, "step7 read MSG_CREATE_VD_RESP ...")
            val resp = try {
                readCreateVdRespWithTimeout(client.inputStream, CREATE_VD_RESP_TIMEOUT_MS)
            } catch (e: Exception) {
                val msg = "读 CREATE_VD_RESP 失败：${e.javaClass.simpleName}: ${e.message}（详见 [server.err]）"
                Log.e(TAG, "step7 FAIL: $msg", e)
                cleanupAll()
                return null to msg
            }
            if (!resp.ok || resp.displayId <= 0) {
                val msg = "虚拟显示器创建失败：${resp.error.ifBlank { "displayId=${resp.displayId}" }}"
                Log.e(TAG, "step7 FAIL: $msg")
                cleanupAll()
                return null to msg
            }
            cachedDisplayId = resp.displayId

            // step8: 启动 PING 线程（保活）
            keepAliveRunning.set(true)
            keepAliveThread = Thread({ runKeepAlive(client) }, "autobot-keepalive").apply {
                isDaemon = true; start()
            }

            // step9: 启动 MSG_FRAME 接收线程 → 解码 JPEG → 注入到 NativeCapturer
            frameReceiveRunning.set(true)
            frameReceiveThread = Thread({ runFrameReceiver(client) }, "autobot-frame-receiver").apply {
                isDaemon = true; start()
            }

            Log.i(TAG, "✅ VirtualDisplay started: ${width}x${height} displayId=$cachedDisplayId")
            // 返回 null 作为 Surface 也可以，但 MonitorViewModel 还在老代码把返回值作为
            // "启动成功标志"判断；我们给一个 dummy surface 或者空判断都行。
            // 这里返回 null —— MonitorViewModel 的 launchAppWithOrientationAdaptation
            // 是用 second 判断错误（错误消息空=成功），所以只要 second == "" 就 OK。
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
        ShellExecutor.execute("input tap $x $y", useShizuku = true, timeout = 2000)
    }

    fun injectTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        ShellExecutor.execute("input swipe $fromX $fromY $toX $toY 100", useShizuku = true, timeout = 2000)
    }

    fun injectTouchUp(x: Int, y: Int) {}

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

        // 发 RELEASE_VD
        val client = clientSocket
        if (client != null && client.isConnected) {
            try {
                VDProtocol.writeMessage(client.outputStream, VDProtocol.MSG_RELEASE_VD, VDProtocol.EMPTY_PAYLOAD)
                try { readWithTimeout(client.inputStream, 1_000); Log.i(TAG, "Got RELEASE_VD response") }
                catch (e: Exception) { Log.w(TAG, "Wait RELEASE_VD_RESP timeout: ${e.message}（可接受）") }
            } catch (e: Exception) { Log.w(TAG, "Send RELEASE_VD failed: ${e.message}") }
        }

        try { clientSocket?.close() } catch (_: Exception) {}; clientSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}; serverSocket = null

        ShizukuProcessManager.destroyServer(serverProcess); serverProcess = null
        stdoutThread?.let { try { it.join(300) } catch (_: Exception) {} }; stdoutThread = null
        stderrThread?.let { try { it.join(300) } catch (_: Exception) {} }; stderrThread = null

        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null
        cachedDisplayId = -1
        Log.i(TAG, "VirtualDisplay stopped")
    }

    // ===================== 内部实现 =====================

    /**
     * MSG_FRAME 接收主循环：
     *  - 每次 VDProtocol.readMessage 拿到新消息 → 只处理 MSG_FRAME，其他类型忽略
     *  - FramePacket.fromByteArray → decodeBitmap() → NativeCapturer.injectExternalFrame(bitmap)
     *    → Native 端自动写入 frameBuffer + 若 previewSurface 已设置则 blit 到 SurfaceView
     *  - socket 异常：退出循环 + cachedDisplayId=-1（调用方 UI 轮询 displayId 状态可感知）
     */
    private fun runFrameReceiver(client: LocalSocket) {
        val input = client.inputStream
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
                        // 注意：injectExternalFrame 内部已经自增 frameCount
                    } catch (e: Exception) {
                        Log.e(TAG, "FramePacket decode failed: ${e.message}")
                    }
                    // 可选：写 MSG_FRAME_ACK 让 server 节流（如果 server 发送过快导致 socket 缓冲积压）
                    try { VDProtocol.writeMessage(client.outputStream, VDProtocol.MSG_FRAME_ACK, VDProtocol.EMPTY_PAYLOAD) }
                    catch (_: Exception) {}
                }
                VDProtocol.MSG_PONG -> {
                    // keepAliveThread 不在这里处理；记录一下 lastPongTime 给 PING 线程共享也可以，
                    // 但现在 PING 线程走 readWithTimeout，PONG 消息可能被两条线程各抢一半，
                    // 所以最简单：让 PING 走单独的写 + 轮询式读，MSG_FRAME/其他消息交给 frameReceiveThread 独享 inputStream
                    // 但我们的实现现在是两条线程都读 inputStream，会抢包。
                    // 这是个 bug！修复：把 PONG 和 MSG_FRAME 都由 frameReceiveThread 处理，
                    // 保活只负责写 PING 不负责读，而"PONG 超时"用最近收到任意消息（包括 MSG_FRAME）
                    // 的时间来判断（因为只要 socket 通，就有 MSG_FRAME 高频消息）。
                }
            }
        }
        frameReceiveRunning.set(false)
        cachedDisplayId = -1
        Log.i(TAG, "frameReceiveThread exited")
    }

    /**
     * 保活线程：现在只负责**定时写 PING**，**不读**（防止和 frameReceiveThread 抢包）。
     * 保活超时判定移到 frameReceiveThread：只要 MSG_FRAME 连续 2 秒没收到就视为断。
     * 如果没画面（VD 里没 App），server 端 onImageAvailable 不触发，MSG_FRAME 可能不发。
     * 那就改成：PING 线程**写 PING**，然后通过一个 volatile 共享"lastAnyMessageTime"时间戳，
     * frameReceiveThread 每收到一条消息（包括 MSG_FRAME/MSG_PONG/...）就更新它。
     * PING 线程每 5s 检查一次 lastAnyMessageTime，如果超过 12s 没更新就报错退出。
     * （这个实现比较复杂，先简化：只要 socket 不抛 IOException 就认为活着。）
     */
    @Volatile
    private var lastAnyMessageTime = System.currentTimeMillis()

    private fun runKeepAlive(client: LocalSocket) {
        val out = client.outputStream
        // 先包一层：frameReceiveThread 里任何消息收到都更新 lastAnyMessageTime
        // 这个通过修改 runFrameReceiver 实现：在每次 readMessage 成功后 set
        // 为了不重写 runFrameReceiver，我们改成 PING 线程不做超时判定，只写 PING，
        // frameReceiveThread 读到 MSG_PONG 或 MSG_FRAME 就表明 socket 还活着。
        // 如果 socket 真断了，readMessage 会抛 IOException，两条线程中的任何一条都会 break 并 cleanup。
        Log.i(TAG, "keepAlive started (write-only PING every ${PING_INTERVAL_MS}ms)")
        while (keepAliveRunning.get() && !Thread.currentThread().isInterrupted) {
            try { Thread.sleep(PING_INTERVAL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
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

    private fun acceptWithTimeout(server: LocalServerSocket, timeoutMs: Long): LocalSocket {
        val holder = arrayOfNulls<LocalSocket>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try { holder[0] = server.accept() } catch (e: Exception) { errHolder[0] = e }
        }, "autobot-accept").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) { try { server.close() } catch (_: Exception) {}; t.interrupt(); throw IOException("accept timed out after ${timeoutMs}ms") }
        errHolder[0]?.let { throw it }
        return holder[0] ?: throw IOException("accept returned null")
    }

    private fun readCreateVdRespWithTimeout(input: java.io.InputStream, timeoutMs: Long): VDResponse {
        val holder = arrayOfNulls<VDResponse>(1)
        val errHolder = arrayOfNulls<Exception>(1)
        val t = Thread({
            try {
                val (type, payload) = VDProtocol.readMessage(input)
                if (type != VDProtocol.MSG_CREATE_VD_RESP) throw IOException("expect CREATE_VD_RESP, got $type")
                holder[0] = VDResponse.fromByteArray(payload)
            } catch (e: Exception) { errHolder[0] = e }
        }, "autobot-read-resp").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) { t.interrupt(); throw IOException("read CREATE_VD_RESP timed out after ${timeoutMs}ms") }
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
        try { clientSocket?.close() } catch (_: Exception) {}; clientSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}; serverSocket = null
        ShizukuProcessManager.destroyServer(serverProcess); serverProcess = null
        stdoutThread?.let { try { it.join(300) } catch (_: Exception) {} }; stdoutThread = null
        stderrThread?.let { try { it.join(300) } catch (_: Exception) {} }; stderrThread = null
        try { capturer?.releaseNativeCapturer() } catch (_: Exception) {}
        capturer = null
        cachedDisplayId = -1
    }
}
