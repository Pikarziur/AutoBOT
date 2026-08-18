package com.autobot.app.server

import android.graphics.ImageFormat
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
 * Server 进程入口（Shizuku.newProcess 启动的 shell uid app_process）
 *
 * ★关键架构变更（修复 LocalSocket Permission denied）★：
 *   旧方案 ❌：App 创建 LocalServerSocket → server 用 LocalSocket.connect()
 *              → Android 10+ SELinux 禁止 shell domain 连接 untrusted_app domain 的
 *                abstract namespace socket → IOException: Permission denied
 *   新方案 ✅（scrcpy 同款架构）：直接用 stdin/stdout pipe
 *     - App 端通过 process.outputStream 写 → server 端 System.in 读
 *     - server 端 System.out.write → App 端 process.inputStream 读
 *     - AndroidRuntime 启动日志和 Android Log 走 logd，不污染 stdout pipe
 *
 * 数据流：
 *   1. App → server：MSG_CREATE_VD / MSG_PING / MSG_FRAME_ACK / MSG_RELEASE_VD
 *   2. server → App：MSG_CREATE_VD_RESP / MSG_FRAME / MSG_PONG / MSG_RELEASE_VD_RESP
 *
 * 关键修复（修复 Parcel.marshall 失败）：
 *   不再接收 App 端 Surface Parcel（Surface 内藏 IGraphicBufferProducer Binder）。
 *   server 端同时持有 VD creator + VD consumer 两端，完全绕开 Surface 跨进程传递死胡同。
 *
 * server 端自己读帧也有额外好处：
 *   - 不需要在独立进程加载 libautobot_native.so（不用处理 JNI class 路径注册问题）
 *   - 全部用 Java/Kotlin 标准 API（minSdk=26 都覆盖 ImageReader/YuvImage/BitmapFactory）
 *   - 崩溃栈更容易在 logcat 的 ServerMain tag 中读出来
 */
object ServerMain {

    private const val TAG = "ServerMain"

    /** 最大 ImageReader 缓冲帧数：3 足够 30fps 不丢帧 */
    private const val MAX_IMAGES = 3

    /** MSG_FRAME 节流：收到的 ACK 数量 / 发送的帧数量 简单节流 */
    @Volatile private var ackCounter = 0L
    @Volatile private var frameIndex = 0L
    @Volatile private var maxFps = 30
    @Volatile private var jpegQuality = 90
    @Volatile private var heldVd: VirtualDisplay? = null
    @Volatile private var heldReader: ImageReader? = null
    @Volatile private var running = true

    /**
     * Server 入口。args 不再传 socketName 参数（用 stdin/stdout pipe 通信）。
     *
     * ★关键：用 System.in / System.out 替代 LocalSocket★
     *   Shizuku.newProcess 返回的 Process 对象的 inputStream/outputStream 自动连接到
     *   server 进程的 System.out / System.in，无需任何 socket 建连，无 SELinux 限制
     */
    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "🚀 start pid=${Process.myPid()} uid=${Process.myUid()}")

        val input: InputStream = System.`in`
        val out: OutputStream = System.out

        try {
            // 首条消息必须是 CREATE_VD
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
                return  // handleCreateVd 里已经写了 MSG_CREATE_VD_RESP（含失败错误）
            }

            // VD 创建成功：进入 keepAlive 循环
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

    /**
     * 处理 CREATE_VD：
     *   - 反序列化 VDRequest → w/h/density/flags/name/jpegQuality/maxFps
     *   - Workarounds.apply + FakeContext
     *   - 反射 DisplayManager(Context) + createVirtualDisplay(name,w,h,dpi,surface,flags)
     *   - 新：ImageReader.newInstance(w,h,YUV_420_888,MAX_IMAGES)
     *   - 新：onImageAvailableListener 监听 new image → compress JPEG → MSG_FRAME
     *
     * @return Pair<vdCreated: Boolean, readerCreated: ImageReader?>
     */
    private fun handleCreateVd(payload: ByteArray, out: OutputStream): Pair<Boolean, ImageReader?> {
        try {
            val req = VDRequest.fromByteArray(payload)
            Log.i(TAG, "CREATE_VD: ${req.width}x${req.height} dpi=${req.density} flags=0x${req.flags.toString(16)} " +
                    "name=${req.name} jpegQ=${req.jpegQuality} maxFps=${req.maxFps}")
            jpegQuality = req.jpegQuality.coerceIn(1, 100)
            maxFps = req.maxFps.coerceIn(1, 60)

            // 3: Workarounds.apply（app_process 没有 ActivityThread，必须注入；Samsung/MIUI 要求 Android 12+）
            try {
                Workarounds.apply()
                Log.i(TAG, "✅ Workarounds.apply() OK")
            } catch (t: Throwable) {
                Log.w(TAG, "Workarounds.apply() failed (continuing with FakeContext fallback)", t)
            }

            // 4: FakeContext
            val fakeCtx = FakeContext.get()
            Log.i(TAG, "✅ FakeContext OK: pkg=${fakeCtx.packageName}")

            // 5: ImageReader（server 端自产自销：生产端是 VD，消费端是 onImageAvailable 读帧）
            // ★关键：用 RGBA_8888 而非 YUV_420_888★
            // VirtualDisplay 在 Android 12+ 上默认输出 RGBA 格式，YUV_420_888 会导致
            // "producer output buffer format 0x1 doesn't match ImageReader's configured buffer format 0x23"
            Log.i(TAG, "ImageReader.newInstance(w=${req.width}, h=${req.height}, fmt=RGBA_8888, maxImages=$MAX_IMAGES) ...")
            val reader = ImageReader.newInstance(req.width, req.height, ImageFormat.RGBA_8888, MAX_IMAGES)
            Log.i(TAG, "✅ ImageReader OK: surface=${reader.surface}")

            // 6: createVirtualDisplay（直接把 ImageReader.surface 给 VD 当输出，不需要跨进程！）
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
            Log.i(TAG, "✅ VD created, displayId=$displayId")

            // 7: 注册 ImageReader.onImageAvailableListener
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
     * 从 ImageReader 拿到最新一帧 → RGBA_8888 → Bitmap → JPEG 压缩 → MSG_FRAME 发 stdout pipe
     * 带简单的节流：minSendIntervalNs 以下的帧直接丢弃（避免 pipe 缓冲积压）
     *
     * 为什么用 RGBA_8888？
     *   VirtualDisplay 在 Android 12+ 上默认输出 RGBA 格式，YUV_420_888 会导致格式不匹配。
     *   RGBA → Bitmap.compress(JPEG) 是最稳健的跨设备 JPEG 压缩方式。
     */
    private var lastSendTimeNs = 0L

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
            val rgbaBuffer = planes[0].buffer  // RGBA_8888 只有 1 个 plane
            val rowStride = planes[0].rowStride
            val rowBytes = w * 4  // RGBA 每像素 4 字节

            // 将 RGBA 数据拷贝到紧凑 ByteBuffer（处理行对齐填充）
            val packedBuffer = java.nio.ByteBuffer.allocate(rowBytes * h)
            val rowBuf = ByteArray(rowStride)
            for (row in 0 until h) {
                rgbaBuffer.position(row * rowStride)
                rgbaBuffer.get(rowBuf, 0, rowBytes)
                packedBuffer.put(rowBuf, 0, rowBytes)
            }
            packedBuffer.flip()

            // 创建 Bitmap 并从 RGBA 数据填充像素
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(packedBuffer)

            // JPEG 压缩
            val jpegBaos = ByteArrayOutputStream(64 * 1024)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, jpegBaos)
            bitmap.recycle()

            val jpegBytes = jpegBaos.toByteArray()
            val idx = frameIndex++
            val pkt = FramePacket(width = w, height = h, jpegBytes = jpegBytes, frameIndex = idx)
            VDProtocol.writeMessage(outPipe, VDProtocol.MSG_FRAME, pkt.toByteArray())
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
        Log.i(TAG, "keepAlive started (ping + release + msg dispatch)")
        while (running && heldVd != null) {
            try {
                val (msgType, _) = VDProtocol.readMessage(input)
                when (msgType) {
                    VDProtocol.MSG_PING -> {
                        try { VDProtocol.writeMessage(out, VDProtocol.MSG_PONG, VDProtocol.EMPTY_PAYLOAD) }
                        catch (_: Exception) { break }
                    }
                    VDProtocol.MSG_FRAME_ACK -> {
                        ackCounter++
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
        // Looper.quit() 让 ImageListenerHandlerThread 自行退出
        try { android.os.Looper.myLooper()?.quitSafely() } catch (_: Exception) {}
    }
}
