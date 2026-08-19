package com.autobot.app.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Parcel
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * App ↔ Server 进程二进制协议（MAA-Meow/scrcpy 同款数据流方向）
 *
 * 帧格式：
 *   [4 字节大端 frame_size] [4 字节大端 type] [4 字节大端 payload_size] [payload bytes]
 *
 * 消息类型：
 *   1. MSG_CREATE_VD         (App → Server) 创建虚拟显示器（不再带 Surface Parcel！）
 *   2. MSG_CREATE_VD_RESP     (Server → App) 创建结果
 *   3. MSG_PING               (App → Server) 心跳
 *   4. MSG_PONG               (Server → App) 心跳响应
 *   5. MSG_RELEASE_VD         (App → Server) 释放虚拟显示器
 *   6. MSG_RELEASE_VD_RESP   (Server → App) 释放完成
 *   7. MSG_FRAME             (Server → App) JPEG 帧（VD 画面内容，高频消息）
 *   8. MSG_FRAME_ACK          (App → Server) 帧已接收（可选，server 做 flow control 节流）
 *
 * 重要架构变更（修复 Parcel.marshall 失败）：
 *   旧方案 ❌：App 创建 AImageReader + Surface.writeToParcel + marshall() → socket → server
 *              → RuntimeException: Parcel contains binders/FDs（Surface 内藏 IGraphicBufferProducer Binder）
 *   新方案 ✅：server 端（shell uid）既创建 VD 也通过 ImageReader 读画面 → 压缩 JPEG →
 *              以 MSG_FRAME 消息通过 socket 发 byte[] → App 端 decodeByteArray → Bitmap
 *              → 注入到 NativeCapturer.frameBuffer（识图 + 预览共用）
 *              （与 scrcpy/MAA-Meow 同款数据流方向，绕开 Surface 跨进程死胡同）
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

    /** 空 payload（PING/PONG/RELEASE_VD/FRAMES_ACK 等占位） */
    val EMPTY_PAYLOAD = ByteArray(0)

    /**
     * 写一帧消息：4 字节大端 frame_size + (4 字节 type + 4 字节 payload_size + payload)
     * 阻塞到写完并 flush。MSG_FRAME 等大帧调用方应自行节流（例如每 33ms 一帧即可）。
     */
    fun writeMessage(out: OutputStream, type: Int, payload: ByteArray) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { d ->
            d.writeInt(type)
            d.writeInt(payload.size)
            d.write(payload)
        }
        val frame = bos.toByteArray()

        val header = ByteArray(4)
        header[0] = (frame.size ushr 24).toByte()
        header[1] = (frame.size ushr 16).toByte()
        header[2] = (frame.size ushr 8).toByte()
        header[3] = frame.size.toByte()

        synchronized(out) {
            out.write(header)
            out.write(frame)
            out.flush()
        }
    }

    /**
     * 阻塞读一帧消息：返回 (type, payload)。EOF/非法帧长度抛 IOException（上层视为 socket 断连）。
     * MSG_FRAME payload 较大（约 50KB~200KB，JPEG）时也安全（16MB upper bound）。
     */
    fun readMessage(input: InputStream): Pair<Int, ByteArray> {
        val header = ByteArray(4)
        readFully(input, header)
        val frameSize = ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)

        if (frameSize < 8 || frameSize > 32 * 1024 * 1024) {
            // MSG_FRAME 单帧 JPEG 540x960 q=90 ~< 200KB；给 32MB 上限防恶意 length
            throw java.io.IOException("Invalid frame size: $frameSize (must be 8..32MB)")
        }

        val frame = ByteArray(frameSize)
        readFully(input, frame)

        DataInputStream(ByteArrayInputStream(frame)).use { d ->
            val type = d.readInt()
            val payloadSize = d.readInt()
            if (payloadSize < 0 || payloadSize > frameSize - 8) {
                throw java.io.IOException("Invalid payload size: $payloadSize (frame=$frameSize)")
            }
            val payload = ByteArray(payloadSize)
            if (payloadSize > 0) {
                readFully(d, payload)
            }
            return type to payload
        }
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
 * 创建虚拟显示器请求
 *
 * 重要变更：**不再带 surfaceBytes**！
 * 旧版带 surfaceBytes → Parcel.marshall() 因 Surface 内藏 Binder/FD 直接抛 RuntimeException。
 * 新版 server 端自己通过 ImageReader.getSurface() 创建 VD 输出 Surface，App 端通过 MSG_FRAME 拿 JPEG 帧。
 */
data class VDRequest(
    val width: Int,
    val height: Int,
    val density: Int,
    val flags: Int,
    val name: String,
    /** JPEG 质量 1~100（默认 90，识别足够）。数值越大帧越大延迟越高 */
    val jpegQuality: Int = 90,
    /** 帧率上限（默认 30fps；识别/点击场景 15fps 也够用，省带宽） */
    val maxFps: Int = 30
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
                return VDRequest(width, height, density, flags, name, jpegQuality, maxFps)
            } finally {
                p.recycle()
            }
        }
    }
}

/**
 * 创建虚拟显示器响应
 */
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
 * 画面帧包（MSG_FRAME payload）
 *
 * Server 端通过 ImageReader 拿到 VD 画面 → 压缩为 JPEG byte[] → 打包成 FramePacket 发 socket。
 * App 端通过 fromByteArray 还原后调用 FramePacket.decodeBitmap() 拿到 Bitmap，
 * 再调用 NativeCapturer.injectExternalFrame(bitmap) 写入到 Native 端的 frameBuffer 供识图 + 预览。
 *
 * 注意：width/height 显式传输是为了 App 端无需 decode 前就能做尺寸校验/内存池复用。
 */
data class FramePacket(
    val width: Int,
    val height: Int,
    /** JPEG 压缩后的字节流（带 SOI/EOI marker，BitmapFactory 能直接解码） */
    val jpegBytes: ByteArray,
    /** 单调递增帧序号，App 端可据此判断是否丢帧 */
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

    /** 解 JPEG 为 Bitmap（纯 Java 标准 API，无 native 依赖） */
    fun decodeBitmap(): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            // 注意：FramePacket 是顶层 data class，无法访问 VDProtocol companion 的 private TAG
            Log.e("VDProtocol", "FramePacket.decodeBitmap failed: ${e.message}")
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

/**
 * 触摸事件（MSG_TOUCH_DOWN / MSG_TOUCH_MOVE / MSG_TOUCH_UP 的 payload）
 *
 * 仅传输 (x, y) 坐标（虚拟显示器坐标系），action 由消息类型区分。
 * 编码方式：DataOutputStream 两个 Int（8 字节），不依赖 Parcel，简洁高效。
 * server 端收到后用 IInputManager.injectInputEvent() 反射注入 MotionEvent 到虚拟显示器。
 */
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
