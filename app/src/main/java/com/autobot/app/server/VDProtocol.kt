package com.autobot.app.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Parcel
import android.util.Log
import com.autobot.app.util.BitmapPool
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * App ↔ Server 进程二进制协议（MAA-Meow/scrcpy 同款数据流方向）。
 *
 * 帧格式：[4 字节大端 frame_size] [4 字节大端 type] [4 字节大端 payload_size] [payload bytes]
 *
 * 踩坑（Parcel.marshall 失败）：旧方案 App 创建 AImageReader + Surface.writeToParcel + marshall()
 * → RuntimeException: Parcel contains binders/FDs（Surface 内藏 IGraphicBufferProducer Binder）。
 * 新方案 server 端既创建 VD 也通过 ImageReader 读画面 → 压缩 JPEG → MSG_FRAME 发 byte[]，
 * 绕开 Surface 跨进程死胡同。
 */
object VDProtocol {

    private const val TAG = "VDProtocol"

    const val MSG_CREATE_VD = 1
    const val MSG_CREATE_VD_RESP = 2
    const val MSG_PING = 3
    const val MSG_PONG = 4
    const val MSG_RELEASE_VD = 5
    const val MSG_RELEASE_VD_RESP = 6
    const val MSG_FRAME = 7
    const val MSG_FRAME_ACK = 8
    const val MSG_TOUCH_DOWN = 9
    const val MSG_TOUCH_MOVE = 10
    const val MSG_TOUCH_UP = 11
    const val MSG_KEY_BACK = 12   // 注入 KEYCODE_BACK 到虚拟显示器（无需 payload，keyCode 固定）

    /** 空 payload（PING/PONG/RELEASE_VD/FRAMES_ACK 等占位）。 */
    val EMPTY_PAYLOAD = ByteArray(0)

    /**
     * 写一帧消息：4 字节大端 frame_size + 4 字节大端 type + 4 字节大端 payload_size + payload。
     * 阻塞到写完并 flush。MSG_FRAME 等大帧调用方应自行节流（例如每 33ms 一帧即可）。
     *
     * CPU 优化：去掉 ByteArrayOutputStream + DataOutputStream + toByteArray() 三层中间分配。
     * 直接构造 12 字节 header，配 2 次 write 调用：
     *   - 12 字节 header（frame_size+type+payload_size）
     *   - payload 字节
     * 原 8MB RGBA 帧需分配 ~16MB 中间 byte[]，现仅 12 字节。
     */
    fun writeMessage(out: OutputStream, type: Int, payload: ByteArray) {
        val payloadSize = payload.size
        val frameSize = 8 + payloadSize  // type(4) + payloadSize(4) + payload

        // 12 字节 header：frame_size(4) + type(4) + payload_size(4)
        val header = ByteArray(12)
        header[0]  = (frameSize ushr 24).toByte()
        header[1]  = (frameSize ushr 16).toByte()
        header[2]  = (frameSize ushr 8).toByte()
        header[3]  = frameSize.toByte()
        header[4]  = (type ushr 24).toByte()
        header[5]  = (type ushr 16).toByte()
        header[6]  = (type ushr 8).toByte()
        header[7]  = type.toByte()
        header[8]  = (payloadSize ushr 24).toByte()
        header[9]  = (payloadSize ushr 16).toByte()
        header[10] = (payloadSize ushr 8).toByte()
        header[11] = payloadSize.toByte()

        synchronized(out) {
            out.write(header)
            if (payloadSize > 0) {
                out.write(payload)
            }
            out.flush()
        }
    }

    /**
     * 阻塞读一帧消息：返回 (type, payload)。EOF/非法帧长度抛 IOException（上层视为 socket 断连）。
     * MSG_FRAME payload 较大（RGBA 直传 8MB 或 JPEG 50-200KB）时也安全（32MB upper bound）。
     *
     * CPU 优化：先读 12 字节 header 拿到 frame_size+type+payload_size，
     * 直接按 payload_size 分配 payload buffer 从 input stream 读，
     * 跳过原方案的 frame = ByteArray(frameSize) 中间缓冲（8MB 帧省 8MB 堆分配）。
     */
    fun readMessage(input: InputStream): Pair<Int, ByteArray> {
        val header = ByteArray(12)
        readFully(input, header)

        val frameSize = ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)

        if (frameSize < 8 || frameSize > 32 * 1024 * 1024) {
            // 32MB 上限防恶意 length（MSG_FRAME 单帧 RGBA 1080P ~8MB，JPEG ~200KB）
            throw java.io.IOException("Invalid frame size: $frameSize (must be 8..32MB)")
        }

        val type = ((header[4].toInt() and 0xff) shl 24) or
                ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or
                (header[7].toInt() and 0xff)

        val payloadSize = ((header[8].toInt() and 0xff) shl 24) or
                ((header[9].toInt() and 0xff) shl 16) or
                ((header[10].toInt() and 0xff) shl 8) or
                (header[11].toInt() and 0xff)

        if (payloadSize < 0 || payloadSize > frameSize - 8) {
            throw java.io.IOException("Invalid payload size: $payloadSize (frame=$frameSize)")
        }

        val payload = ByteArray(payloadSize)
        if (payloadSize > 0) {
            readFully(input, payload)
        }
        return type to payload
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read < 0) {
                throw java.io.IOException("EOF reached before reading ${buf.size} bytes (got $offset)")
            }
            offset += read
        }
    }
}

/**
 * 创建虚拟显示器请求。
 *
 * 踩坑：不再带 surfaceBytes！旧版带 surfaceBytes → Parcel.marshall() 因 Surface 内藏 Binder/FD
 * 直接抛 RuntimeException。新版 server 端通过 ImageReader.getSurface() 创建 VD 输出 Surface。
 */
data class VDRequest(
    val width: Int,
    val height: Int,
    val density: Int,
    val flags: Int,
    val name: String,
    /** JPEG 质量 1~100（默认 90，识别足够）。数值越大帧越大延迟越高。 */
    val jpegQuality: Int = 90,
    /** 帧率上限（默认 30fps；识别/点击场景 15fps 也够用，省带宽）。 */
    val maxFps: Int = 30,
    /**
     * 帧格式：0=JPEG（兼容旧路径），1=RGBA 原始字节直传（CPU 优化，跳过 JPEG 编解码）。
     * RGBA 直传下每帧字节 = width*height*4，1080P ≈ 8MB，需配套降低 maxFps 避免 pipe 带宽打满。
     */
    val frameFormat: Int = 0
) {
    fun toByteArray(): ByteArray {
        val p = Parcel.obtain()
        try {
            p.writeInt(width)
            p.writeInt(height)
            p.writeInt(density)
            p.writeInt(flags)
            p.writeString(name)
            p.writeInt(jpegQuality)
            p.writeInt(maxFps)
            p.writeInt(frameFormat)
            p.setDataPosition(0)
            return p.marshall()
        } finally {
            p.recycle()
        }
    }

    companion object {
        fun fromByteArray(data: ByteArray): VDRequest {
            val p = Parcel.obtain()
            try {
                p.unmarshall(data, 0, data.size)
                p.setDataPosition(0)
                val width = p.readInt()
                val height = p.readInt()
                val density = p.readInt()
                val flags = p.readInt()
                val name = p.readString() ?: "AutoBOT-VirtualDisplay"
                val jpegQuality = p.readInt().coerceIn(1, 100)
                val maxFps = p.readInt().coerceIn(1, 60)
                // 兼容旧版 server 不写 frameFormat 字段的情况：dataAvail 检查后读取
                val frameFormat = if (p.dataAvail() >= 4) p.readInt() else 0
                return VDRequest(width, height, density, flags, name, jpegQuality, maxFps, frameFormat)
            } finally {
                p.recycle()
            }
        }
    }
}

/** 创建虚拟显示器响应。 */
data class VDResponse(
    val ok: Boolean,
    val displayId: Int,
    val error: String
) {
    fun toByteArray(): ByteArray {
        val p = Parcel.obtain()
        try {
            p.writeInt(if (ok) 1 else 0)
            p.writeInt(displayId)
            p.writeString(error)
            p.setDataPosition(0)
            return p.marshall()
        } finally {
            p.recycle()
        }
    }

    companion object {
        fun fromByteArray(data: ByteArray): VDResponse {
            val p = Parcel.obtain()
            try {
                p.unmarshall(data, 0, data.size)
                p.setDataPosition(0)
                val ok = p.readInt() == 1
                val displayId = p.readInt()
                val error = p.readString() ?: ""
                return VDResponse(ok, displayId, error)
            } finally {
                p.recycle()
            }
        }
    }
}

/**
 * 画面帧包（MSG_FRAME payload）。
 *
 * 注意：width/height 显式传输是为了 App 端无需 decode 前就能做尺寸校验/内存池复用。
 */
data class FramePacket(
    val width: Int,
    val height: Int,
    /** JPEG 压缩后的字节流（带 SOI/EOI marker，BitmapFactory 能直接解码）。 */
    val jpegBytes: ByteArray,
    /** 单调递增帧序号，App 端可据此判断是否丢帧。 */
    val frameIndex: Long
) {
    fun toByteArray(): ByteArray {
        val p = Parcel.obtain()
        try {
            p.writeInt(width)
            p.writeInt(height)
            p.writeInt(jpegBytes.size)
            p.writeByteArray(jpegBytes)
            p.writeLong(frameIndex)
            p.setDataPosition(0)
            return p.marshall()
        } finally {
            p.recycle()
        }
    }

    /**
     * 解 JPEG 为 Bitmap（纯 Java 标准 API，无 native 依赖）。
     *
     * CPU 优化：优先使用 [BitmapPool] 复用 native 像素内存，避免每帧重新分配 ~8MB 堆外内存。
     * 池子提供 inBitmap 时第一次解码可能因尺寸差异抛 IllegalArgumentException，
     * 此时清空 inBitmap 重试一次（fallback 到普通解码）。
     */
    fun decodeBitmap(): Bitmap? {
        val opts = BitmapFactory.Options()
        BitmapPool.applyToOptions(opts)
        try {
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
            if (bmp != null) return bmp
        } catch (e: IllegalArgumentException) {
            // inBitmap 尺寸不匹配：清空 inBitmap 重试一次
            Log.w("VDProtocol", "decodeBitmap inBitmap mismatch, fallback: ${e.message}")
            opts.inBitmap = null
        } catch (e: Exception) {
            Log.e("VDProtocol", "FramePacket.decodeBitmap failed: ${e.message}")
            return null
        }
        return try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
        } catch (e: Exception) {
            Log.e("VDProtocol", "FramePacket.decodeBitmap fallback failed: ${e.message}")
            null
        }
    }

    companion object {
        fun fromByteArray(data: ByteArray): FramePacket {
            val p = Parcel.obtain()
            try {
                p.unmarshall(data, 0, data.size)
                p.setDataPosition(0)
                val width = p.readInt()
                val height = p.readInt()
                val jpegSize = p.readInt()
                val jpegBytes = ByteArray(jpegSize)
                p.readByteArray(jpegBytes)
                val frameIndex = p.readLong()
                return FramePacket(width, height, jpegBytes, frameIndex)
            } finally {
                p.recycle()
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FramePacket) return false
        return width == other.width && height == other.height &&
                frameIndex == other.frameIndex && jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + jpegBytes.contentHashCode()
        result = 31 * result + frameIndex.hashCode()
        return result
    }
}

/** 触摸事件（MSG_TOUCH_DOWN / MSG_TOUCH_MOVE / MSG_TOUCH_UP 的 payload）；action 由消息类型区分。 */
data class TouchEvent(val x: Int, val y: Int) {
    fun toByteArray(): ByteArray {
        val bos = ByteArrayOutputStream(8)
        DataOutputStream(bos).use { d ->
            d.writeInt(x)
            d.writeInt(y)
        }
        return bos.toByteArray()
    }

    companion object {
        fun fromByteArray(data: ByteArray): TouchEvent {
            DataInputStream(ByteArrayInputStream(data)).use { d ->
                return TouchEvent(d.readInt(), d.readInt())
            }
        }
    }
}
