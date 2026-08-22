package com.autobot.app.server

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Process
import android.util.Log
import com.autobot.app.third.DisplayManagerHelper
import com.autobot.app.third.FakeContext
import com.autobot.app.third.Workarounds
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Server 进程入口（Shizuku.newProcess 启动的 shell uid app_process）。
 *
 * 踩坑 1（LocalSocket Permission denied）：旧方案 App 建 LocalServerSocket、server 用
 * LocalSocket.connect() → Android 10+ SELinux 禁止 shell domain 连 untrusted_app domain
 * 的 abstract namespace socket → IOException。新方案改用 stdin/stdout pipe（scrcpy 同款）。
 *
 * 踩坑 2（Parcel.marshall 失败）：不再接收 App 端 Surface Parcel（Surface 内藏
 * IGraphicBufferProducer Binder）；server 端同时持有 VD creator + consumer 两端，
 * 绕开 Surface 跨进程传递死胡同。
 */
object ServerMain {

    private const val TAG = "ServerMain"

    /** 最大 ImageReader 缓冲帧数：3 足够 30fps 不丢帧。 */
    private const val MAX_IMAGES = 3

    @Volatile private var ackCounter = 0L
    @Volatile private var frameIndex = 0L
    @Volatile private var maxFps = 30
    @Volatile private var jpegQuality = 90
    /** 帧格式：0=JPEG，1=RGBA 直传（CPU 优化，跳过 JPEG 编解码） */
    @Volatile private var frameFormat = 0
    @Volatile private var heldVd: VirtualDisplay? = null
    @Volatile private var heldReader: ImageReader? = null
    @Volatile private var running = true
    @Volatile private var cachedDisplayId = -1

    /** Server 入口；用 System.in / System.out 替代 LocalSocket（Shizuku.newProcess 的 pipe 直连）。 */
    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "🚀 start pid=${Process.myPid()} uid=${Process.myUid()}")

        val input: InputStream = System.`in`
        val out: OutputStream = System.out

        try {
            val (msgType, payload) = try {
                VDProtocol.readMessage(input)
            } catch (e: Exception) {
                Log.e(TAG, "Read first msg failed", e)
                writeResp(out, ok = false, displayId = -1,
                    "Server 读首条消息失败: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
            if (msgType != VDProtocol.MSG_CREATE_VD) {
                writeResp(out, ok = false, displayId = -1, "首条消息类型不对: $msgType")
                return
            }

            val (vdCreated, readerCreated) = handleCreateVd(payload, out)
            if (!vdCreated || readerCreated == null) {
                return
            }

            runKeepAliveLoop(input, out, readerCreated)

        } catch (t: Throwable) {
            Log.e(TAG, "ServerMain fatal", t)
        } finally {
            Log.i(TAG, "ServerMain exit, releasing VD/ImageReader")
            releaseAll()
            // 不再 close socket（stdin/stdout pipe 由 App 端 process.destroy() 关闭）
            running = false
        }
    }

    /** 处理 CREATE_VD：Workarounds + FakeContext + ImageReader + createVirtualDisplay + 帧监听。 */
    private fun handleCreateVd(payload: ByteArray, out: OutputStream): Pair<Boolean, ImageReader?> {
        try {
            val req = VDRequest.fromByteArray(payload)
            Log.i(TAG, "CREATE_VD: ${req.width}x${req.height} dpi=${req.density} flags=0x${req.flags.toString(16)} " +
                    "name=${req.name} jpegQ=${req.jpegQuality} maxFps=${req.maxFps}")
            jpegQuality = req.jpegQuality.coerceIn(1, 100)
            maxFps = req.maxFps.coerceIn(1, 60)
            frameFormat = req.frameFormat.coerceIn(0, 1)

            // Workarounds.apply：app_process 没有 ActivityThread 必须注入；Samsung/MIUI 要求 Android 12+
            try {
                Workarounds.apply()
                Log.i(TAG, "✅ Workarounds.apply() OK")
            } catch (t: Throwable) {
                Log.w(TAG, "Workarounds.apply() failed (continuing with FakeContext fallback)", t)
            }

            val fakeCtx = FakeContext.get()
            Log.i(TAG, "✅ FakeContext OK: pkg=${fakeCtx.packageName}")

            // ★关键踩坑：用 PixelFormat.RGBA_8888 而非 YUV_420_888★
            // VirtualDisplay 在 Android 12+ 上默认输出 RGBA 格式，YUV_420_888 会导致
            // "producer output buffer format 0x1 doesn't match ImageReader's configured buffer format 0x23"
            Log.i(TAG, "ImageReader.newInstance(w=${req.width}, h=${req.height}, fmt=RGBA_8888, maxImages=$MAX_IMAGES) ...")
            val reader = ImageReader.newInstance(req.width, req.height, PixelFormat.RGBA_8888, MAX_IMAGES)
            Log.i(TAG, "✅ ImageReader OK: surface=${reader.surface}")

            val vd = DisplayManagerHelper.createVirtualDisplay(
                surface = reader.surface,
                name = req.name,
                width = req.width,
                height = req.height,
                density = req.density,
                flags = req.flags
            )
            if (vd == null) {
                writeResp(out, ok = false, displayId = -1,
                    "DisplayManagerHelper.createVirtualDisplay 返回 null（详见 logcat DisplayMgrHelper 标签）")
                try { reader.close() } catch (_: Exception) {}
                return false to null
            }
            heldVd = vd
            heldReader = reader
            val displayId = vd.display?.displayId ?: -1
            cachedDisplayId = displayId
            Log.i(TAG, "✅ VD created, displayId=$displayId")

            // 注意：必须用单独的 Looper 线程注册 listener，否则 onImageAvailable 不触发
            startImageListenerThread(reader, out, 1_000_000_000L / maxFps)

            writeResp(out, ok = true, displayId = displayId, error = "")
            return true to reader

        } catch (t: Throwable) {
            Log.e(TAG, "handleCreateVd exception", t)
            var cause: Throwable? = t
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            val detail = if (cause != null && cause !== t) "（根因：${cause.javaClass.simpleName}: ${cause.message}）" else ""
            writeResp(out, ok = false, displayId = -1,
                "Server CREATE_VD 异常: ${t.javaClass.simpleName}: ${t.message} $detail")
            return false to null
        }
    }

    /**
     * 为 ImageReader 启动一个带 Looper 的 HandlerThread，保证 onImageAvailable 被调用。
     * 如果 handler=null，ImageReader 会尝试用当前线程 Looper，没有就静默丢回调。
     */
    private fun startImageListenerThread(reader: ImageReader, outPipe: OutputStream, minSendIntervalNs: Long) {
        val t = object : Thread("autobot-img-listener") {
            override fun run() {
                android.os.Looper.prepare()
                val handler = android.os.Handler(android.os.Looper.myLooper()!!)
                reader.setOnImageAvailableListener({ r ->
                    trySendFrame(r, outPipe, minSendIntervalNs)
                }, handler)
                Log.i(TAG, "ImageListenerHandlerThread started, waiting for frames...")
                android.os.Looper.loop()
                Log.i(TAG, "ImageListenerHandlerThread exited (Looper quit)")
            }
        }
        t.isDaemon = true
        t.start()
    }

    /**
     * 从 ImageReader 拿最新一帧 → RGBA_8888 → 按 frameFormat 决定路径发 stdout pipe：
     *   - frameFormat=1（RGBA 直传，CPU 优化）：去 rowStride padding 后直接写 pipe，跳过 Bitmap/JPEG
     *   - frameFormat=0（JPEG 兼容）：RGBA → Bitmap → JPEG 压缩 → 写 pipe
     * minSendIntervalNs 以下的帧直接丢弃（避免 pipe 缓冲积压）。
     *
     * CPU 优化要点：
     *   1. 帧字节缓冲 frameBytesCache / 行缓冲 rowBufCache 跨帧复用（避免 30fps 下每秒 60 段堆分配）
     *   2. RGBA 直传路径不创建 Bitmap、不调 Bitmap.compress，单帧 CPU 从 ~10-20ms 降到 ~2ms
     */
    private var lastSendTimeNs = 0L

    /** 跨帧复用：packed RGBA 字节缓冲，VD 尺寸不变即复用 */
    private var frameBytesCache: ByteArray? = null
    /** 跨帧复用：行拷贝临时缓冲（rowStride != rowBytes 时使用） */
    private var rowBufCache: ByteArray? = null
    /** 跨帧复用：JPEG 路径专用 ByteArrayOutputStream */
    private var jpegBaosCache: ByteArrayOutputStream? = null

    private fun trySendFrame(reader: ImageReader, outPipe: OutputStream, minSendIntervalNs: Long) {
        val now = System.nanoTime()
        if (now - lastSendTimeNs < minSendIntervalNs) {
            val img: Image? = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            img?.close()
            return
        }
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            return
        } ?: return

        try {
            val w = image.width
            val h = image.height
            val planes = image.planes
            val rgbaBuffer = planes[0].buffer
            val rowStride = planes[0].rowStride
            val rowBytes = w * 4
            val packedSize = rowBytes * h

            // 缓冲区复用：尺寸不变直接复用，避免每帧重新分配
            val frameBytes = frameBytesCache?.takeIf { it.size == packedSize }
                ?: ByteArray(packedSize).also { frameBytesCache = it }
            if (rowStride == rowBytes) {
                // 行对齐完美，整块拷贝
                rgbaBuffer.position(0)
                rgbaBuffer.get(frameBytes, 0, packedSize)
            } else {
                // 按行拷贝去掉 padding
                val rowBuf = rowBufCache?.takeIf { it.size >= rowStride }
                    ?: ByteArray(rowStride).also { rowBufCache = it }
                for (row in 0 until h) {
                    rgbaBuffer.position(row * rowStride)
                    rgbaBuffer.get(rowBuf, 0, rowBytes)
                    System.arraycopy(rowBuf, 0, frameBytes, row * rowBytes, rowBytes)
                }
            }

            val idx = frameIndex++

            if (frameFormat == 1) {
                // RGBA 直传：跳过 Bitmap + JPEG，CPU 大幅节省
                val pkt = FramePacket(width = w, height = h, jpegBytes = frameBytes, frameIndex = idx)
                VDProtocol.writeMessage(outPipe, VDProtocol.MSG_FRAME, pkt.toByteArray())
            } else {
                // JPEG 路径（兼容）
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(frameBytes))
                val baos = jpegBaosCache?.takeIf { it.size() == 0 }?.also { it.reset() }
                    ?: ByteArrayOutputStream(64 * 1024).also { jpegBaosCache = it }
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                bitmap.recycle()
                val jpegBytes = baos.toByteArray()
                val pkt = FramePacket(width = w, height = h, jpegBytes = jpegBytes, frameIndex = idx)
                VDProtocol.writeMessage(outPipe, VDProtocol.MSG_FRAME, pkt.toByteArray())
            }
            lastSendTimeNs = now
        } catch (e: Exception) {
            Log.w(TAG, "trySendFrame failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun writeResp(out: OutputStream, ok: Boolean, displayId: Int, error: String) {
        try {
            VDProtocol.writeMessage(out, VDProtocol.MSG_CREATE_VD_RESP,
                VDResponse(ok, displayId, error).toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write MSG_CREATE_VD_RESP", e)
        }
    }

    private fun runKeepAliveLoop(input: InputStream, out: OutputStream, reader: ImageReader) {
        Log.i(TAG, "keepAlive started (ping + release + touch + msg dispatch)")
        while (running && heldVd != null) {
            try {
                val (msgType, payload) = VDProtocol.readMessage(input)
                when (msgType) {
                    VDProtocol.MSG_PING -> {
                        try { VDProtocol.writeMessage(out, VDProtocol.MSG_PONG, VDProtocol.EMPTY_PAYLOAD) }
                        catch (_: Exception) { break }
                    }
                    VDProtocol.MSG_FRAME_ACK -> {
                        ackCounter++
                    }
                    VDProtocol.MSG_TOUCH_DOWN -> {
                        val ev = TouchEvent.fromByteArray(payload)
                        injectMotionEvent(android.view.MotionEvent.ACTION_DOWN, ev.x, ev.y)
                    }
                    VDProtocol.MSG_TOUCH_MOVE -> {
                        val ev = TouchEvent.fromByteArray(payload)
                        injectMotionEvent(android.view.MotionEvent.ACTION_MOVE, ev.x, ev.y)
                    }
                    VDProtocol.MSG_TOUCH_UP -> {
                        val ev = TouchEvent.fromByteArray(payload)
                        injectMotionEvent(android.view.MotionEvent.ACTION_UP, ev.x, ev.y)
                    }
                    VDProtocol.MSG_KEY_BACK -> {
                        injectKeyBack()
                    }
                    VDProtocol.MSG_RELEASE_VD -> {
                        Log.i(TAG, "Received RELEASE_VD, releasing VD and exiting...")
                        releaseAll()
                        try { VDProtocol.writeMessage(out, VDProtocol.MSG_RELEASE_VD_RESP, VDProtocol.EMPTY_PAYLOAD) }
                        catch (_: Exception) {}
                        running = false
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "keepAlive pipe exception: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
        }
        Log.i(TAG, "keepAlive exited")
    }

    private fun releaseAll() {
        try { heldVd?.release() } catch (e: Exception) { Log.w(TAG, "VD release: ${e.message}") }
        heldVd = null
        try { heldReader?.close() } catch (e: Exception) { Log.w(TAG, "ImageReader close: ${e.message}") }
        heldReader = null
        try { android.os.Looper.myLooper()?.quitSafely() } catch (_: Exception) {}
    }

    private var inputManagerInstance: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var setDisplayIdMethod: java.lang.reflect.Method? = null       // MotionEvent.setDisplayId
    private var setDisplayIdKeyEventMethod: java.lang.reflect.Method? = null  // KeyEvent.setDisplayId
    private var inputManagerInited = false
    /** Android 17+ 移除了 InputManager.getInstance 反射，fallback 到 shell input -d 命令注入 */
    @Volatile private var useShellInjection = false
    /** shell fallback 下缓存的 DOWN 信息（用于 MOVE 攒到 UP 时发 swipe） */
    private data class PendingDown(val x: Int, val y: Int, val atMs: Long)
    @Volatile private var pendingDown: PendingDown? = null
    /** shell fallback 下最后一次 MOVE 的坐标（UP 时作为 swipe 终点） */
    @Volatile private var lastMove: Pair<Int, Int>? = null

    /**
     * 使用 IInputManager.injectInputEvent() 反射注入 MotionEvent 到虚拟显示器（MAA-Meow/scrcpy 同款）。
     *
     * API 兼容性：
     *   - InputManager.getInstance() / MotionEvent.setDisplayId(int) / injectInputEvent(event, mode) 均 @hide，需反射
     *   - MotionEvent.setDisplayId API 30+ 有 public 版本，反射兜底更安全
     *   - mode=0 = INJECT_INPUT_EVENT_MODE_ASYNC，异步注入不阻塞
     *   - shell uid 持有 INJECT_INPUT_EVENTS 权限，不会被拒绝
     *
     * 反射结果缓存到 inputManagerInstance / injectMethod / setDisplayIdMethod，首次调用初始化。
     */
    private fun injectMotionEvent(action: Int, x: Int, y: Int) {
        val displayId = cachedDisplayId
        if (displayId < 0) {
            Log.w(TAG, "injectMotionEvent: displayId=$displayId, skip")
            return
        }

        try {
            ensureInputManagerReflection()

            if (useShellInjection) {
                shellInjectMotion(action, x, y)
                return
            }

            val im = inputManagerInstance ?: return
            val inject = injectMethod ?: return
            val setDisplayId = setDisplayIdMethod

            val now = android.os.SystemClock.uptimeMillis()
            val event = android.view.MotionEvent.obtain(
                now, now, action,
                x.toFloat(), y.toFloat(), 0
            )
            try {
                setDisplayId?.invoke(event, displayId)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                // 异步注入 (mode=0 = INJECT_INPUT_EVENT_MODE_ASYNC)
                inject.invoke(im, event, 0)
            } finally {
                event.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectMotionEvent failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 注入 KEYCODE_BACK 到虚拟显示器（一次完整按键 = ACTION_DOWN + ACTION_UP）。
     * 复用 [ensureInputManagerReflection] 的反射缓存，setDisplayId 用 KeyEvent 专用版本。
     *
     * API 兼容性：
     *   - 注意：KeyEvent 没有 obtain(long,...) static 工厂（MotionEvent 才有），必须用 constructor
     *     直接构造：KeyEvent(downTime, eventTime, action, keyCode, repeat, metaState) — API 1+
     *   - DOWN 与 UP 之间间隔 50ms，模拟一次按键
     *   - shell uid 持有 INJECT_INPUT_EVENTS 权限，注入 KeyEvent 同 MotionEvent 一样被允许
     */
    private fun injectKeyBack() {
        val displayId = cachedDisplayId
        if (displayId < 0) {
            Log.w(TAG, "injectKeyBack: displayId=$displayId, skip")
            return
        }

        try {
            ensureInputManagerReflection()

            if (useShellInjection) {
                shellInjectKeyBack()
                return
            }

            val im = inputManagerInstance ?: return
            val inject = injectMethod ?: return
            val setDisplayId = setDisplayIdKeyEventMethod

            val now = android.os.SystemClock.uptimeMillis()
            val downEvent = android.view.KeyEvent(
                now, now,
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_BACK,
                0,  // repeat
                0   // metaState
            )
            val upEvent = android.view.KeyEvent(
                now, now + 50,  // UP 比 DOWN 晚 50ms
                android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_BACK,
                0,  // repeat
                0   // metaState
            )
            setDisplayId?.invoke(downEvent, displayId)
            setDisplayId?.invoke(upEvent, displayId)
            downEvent.source = android.view.InputDevice.SOURCE_KEYBOARD
            upEvent.source = android.view.InputDevice.SOURCE_KEYBOARD
            // 异步注入 (mode=0 = INJECT_INPUT_EVENT_MODE_ASYNC)
            inject.invoke(im, downEvent, 0)
            inject.invoke(im, upEvent, 0)
            Log.i(TAG, "✅ KEYCODE_BACK injected to displayId=$displayId")
            // 注意：KeyEvent.recycle() 在 API 35 已移除，KeyEvent 较轻量由 GC 回收
            // （MotionEvent.recycle() 仍保留，因为 MotionEvent 包含 native 指针表）
        } catch (e: Exception) {
            Log.w(TAG, "injectKeyBack failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 首次调用时初始化 InputManager 反射缓存（幂等：inputManagerInited 标记位防重复）。
     *
     * 真实注入策略（对照 MAA-Meow InputControlUtils / third.wrappers.InputManager 实现）：
     *   1) 优先：通过 Context.getSystemService(INPUT_SERVICE) 获取公开 InputManager 实例
     *      —— MAA-Meow 同款，Android 1~35+ 都有效，完全绕过 Android 17 移除的
     *      InputManager.getInstance() @hide 静态方法反射。
     *   2) 兼容兜底：老设备上用 InputManager.getInstance() @hide 反射
     *   3) 最后兜底：都失败时 fallback 到 shell "input -d <displayId>" 命令
     */
    private fun ensureInputManagerReflection() {
        if (inputManagerInited) return
        inputManagerInited = true

        val imClass = try {
            Class.forName("android.hardware.input.InputManager")
        } catch (_: Throwable) {
            null
        }

        var acquired: Any? = null
        var lastErr: String? = null

        // --- 策略 1（MAA-Meow 同款）：Context.getSystemService(INPUT_SERVICE) ---
        if (acquired == null) {
            try {
                val ctx = FakeContext.get()
                val svc = ctx.getSystemService(Context.INPUT_SERVICE)
                if (svc != null && imClass != null && imClass.isInstance(svc)) {
                    acquired = svc
                    Log.i(TAG, "✅ InputManager acquired via Context.getSystemService(INPUT_SERVICE) " +
                            "(MAA-Meow compatible path)")
                } else if (svc != null) {
                    Log.w(TAG, "getSystemService(INPUT_SERVICE) returned ${svc.javaClass.name}, " +
                            "not assignable to android.hardware.input.InputManager, continue fallback")
                }
            } catch (e: Throwable) {
                lastErr = "getSystemService: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "InputManager via getSystemService failed: $lastErr")
            }
        }

        // --- 策略 2（老兼容）：反射 InputManager.getInstance() @hide ---
        if (acquired == null && imClass != null) {
            try {
                val getInstance = imClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                acquired = getInstance.invoke(null)
                Log.i(TAG, "✅ InputManager acquired via reflection getInstance() (legacy path)")
            } catch (e: Throwable) {
                lastErr = (lastErr ?: "") + "; getInstance: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "InputManager.getInstance reflection failed: " +
                        "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (acquired != null && imClass != null) {
            try {
                inputManagerInstance = acquired
                injectMethod = imClass.getMethod("injectInputEvent",
                    android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                // MotionEvent.setDisplayId(int) — API 30 public, API 26-29 @hide
                setDisplayIdMethod = android.view.MotionEvent::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                // KeyEvent.setDisplayId(int) — API 30 public, API 26-29 @hide
                setDisplayIdKeyEventMethod = android.view.KeyEvent::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                useShellInjection = false
                Log.i(TAG, "✅ IInputManager reflection initialized (injectInputEvent + setDisplayId)")
                return
            } catch (e: Throwable) {
                lastErr = (lastErr ?: "") + "; inject/setDisplayId: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "injectInputEvent / setDisplayId reflection failed: " +
                        "${e.javaClass.simpleName}: ${e.message}")
                inputManagerInstance = null
                injectMethod = null
                setDisplayIdMethod = null
                setDisplayIdKeyEventMethod = null
            }
        }

        // --- 策略 3（最后兜底）：shell "input -d" 命令注入 ---
        useShellInjection = true
        Log.w(TAG, "InputManager injection paths all failed, fallback to shell input -d: $lastErr")
    }

    // ---- Shell fallback 注入（Android 17+ IInputManager 反射失效时启用） ----

    /** 执行 shell 命令并等待完成，返回是否成功。禁止在主线程调用。 */
    private fun execShell(cmd: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val ok = proc.waitFor() == 0
            if (!ok) {
                val err = proc.errorStream.bufferedReader().readText().trim()
                if (err.isNotBlank()) Log.w(TAG, "execShell failed: $err")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "execShell(${cmd.take(60)}): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 使用 shell "input -d" 命令注入触摸。
     * - DOWN：仅缓存起点，不立刻发（input tap/swipe 都需要完整手势）
     * - MOVE：仅更新 lastMove（最终 UP 时一起 swipe）
     * - UP / CANCEL：如果有移动就 swipe，否则 tap
     */
    private fun shellInjectMotion(action: Int, x: Int, y: Int) {
        val displayId = cachedDisplayId
        if (displayId < 0) return
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pendingDown = PendingDown(x, y, android.os.SystemClock.uptimeMillis())
                lastMove = null
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                lastMove = x to y
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val down = pendingDown
                pendingDown = null
                val endPt = lastMove ?: (x to y)
                lastMove = null
                val durationMs = down?.let { (android.os.SystemClock.uptimeMillis() - it.atMs).toInt().coerceIn(30, 2000) } ?: 50
                if (down != null && (down.x != endPt.first || down.y != endPt.second || durationMs > 120)) {
                    execShell("input -d $displayId swipe ${down.x} ${down.y} ${endPt.first} ${endPt.second} $durationMs")
                } else if (down != null) {
                    execShell("input -d $displayId tap ${down.x} ${down.y}")
                }
            }
        }
    }

    private fun shellInjectKeyBack() {
        val displayId = cachedDisplayId
        if (displayId < 0) return
        execShell("input -d $displayId keyevent KEYCODE_BACK")
    }
}
