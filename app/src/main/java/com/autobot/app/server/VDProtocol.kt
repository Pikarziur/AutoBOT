package com.autobot.app.server

import android.os.Parcel
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * App ↔ Server 进程二进制协议
 *
 * 帧格式（沿用 scrcpy 风格的长度前缀 + 内部 type/payload）：
 *   [4 字节大端 frame_size] [4 字节大端 type] [4 字节大端 payload_size] [payload bytes]
 *
 * 消息类型：
 *   1. MSG_CREATE_VD         (App → Server) 创建虚拟显示器
 *   2. MSG_CREATE_VD_RESP     (Server → App) 创建结果
 *   3. MSG_PING               (App → Server) 心跳
 *   4. MSG_PONG               (Server → App) 心跳响应
 *   5. MSG_RELEASE_VD         (App → Server) 释放虚拟显示器
 *   6. MSG_RELEASE_VD_RESP   (Server → App) 释放完成
 *
 * 设计原则：
 *  - 复用 Android Parcel 机制序列化 Surface / 复合数据，避免手写 byte layout
 *  - 长度前缀让读取端能预先分配缓冲区，防止 socket 流分块导致 read() 截断
 *  - type 字段独立于 payload，方便服务端先看类型再决定是否解析 payload
 */
object VDProtocol {

    private const val TAG = "VDProtocol"

    const val MSG_CREATE_VD = 1
    const val MSG_CREATE_VD_RESP = 2
    const val MSG_PING = 3
    const val MSG_PONG = 4
    const val MSG_RELEASE_VD = 5
    const val MSG_RELEASE_VD_RESP = 6

    /** 空 payload，PING/PONG/RELEASE_VD 都用这个占位 */
    val EMPTY_PAYLOAD = ByteArray(0)

    /**
     * 写一帧消息：4 字节 frame_size + (4 字节 type + 4 字节 payload_size + payload)
     * 阻塞直到所有字节写入 OutputStream 并 flush。
     */
    fun writeMessage(out: OutputStream, type: Int, payload: ByteArray) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { d ->
            d.writeInt(type)
            d.writeInt(payload.size)
            d.write(payload)
        }
        val frame = bos.toByteArray()

        // 4 字节大端长度前缀
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
     * 阻塞读一帧消息：返回 (type, payload)。
     * 任何 IO 异常 / EOF / 帧长度非法都抛 IOException 让上层处理 socket 断连。
     */
    fun readMessage(input: InputStream): Pair<Int, ByteArray> {
        val header = ByteArray(4)
        readFully(input, header)
        val frameSize = ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)

        if (frameSize < 8 || frameSize > 16 * 1024 * 1024) {
            // 防御：避免恶意/损坏的 length 导致分配超大内存
            throw java.io.IOException("Invalid frame size: $frameSize (must be 8..16MB)")
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
                readFully(d, payload)  // 实际从 ByteArrayInputStream 读，肯定能读满
            }
            return type to payload
        }
    }

    /** 阻塞读取完全 n 个字节；EOF 时抛 IOException 让上层判定 socket 断连 */
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
 * @param width       虚拟显示器宽度（像素）
 * @param height      虚拟显示器高度（像素）
 * @param density     DPI
 * @param flags       VirtualDisplay 标志位（由 DisplayManagerHelper.buildDisplayFlags() 构造）
 * @param name        显示器名称
 * @param surfaceBytes Surface.writeToParcel 后 marshalled 出来的字节流
 */
data class VDRequest(
    val width: Int,
    val height: Int,
    val density: Int,
    val flags: Int,
    val name: String,
    val surfaceBytes: ByteArray
) {
    /** 序列化为 ByteArray：用 Android Parcel 自带的二进制格式 */
    fun toByteArray(): ByteArray {
        val p = Parcel.obtain()
        try {
            p.writeInt(width)
            p.writeInt(height)
            p.writeInt(density)
            p.writeInt(flags)
            p.writeString(name)
            p.writeInt(surfaceBytes.size)
            p.writeByteArray(surfaceBytes)
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
                val surfaceSize = p.readInt()
                val surfaceBytes = ByteArray(surfaceSize)
                p.readByteArray(surfaceBytes)
                return VDRequest(width, height, density, flags, name, surfaceBytes)
            } finally {
                p.recycle()
            }
        }
    }

    // data class 自动生成的 equals/hashCode 含数组引用比较，重写为内容比较避免误判
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VDRequest) return false
        return width == other.width && height == other.height && density == other.density &&
                flags == other.flags && name == other.name &&
                surfaceBytes.contentEquals(other.surfaceBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + density
        result = 31 * result + flags
        result = 31 * result + name.hashCode()
        result = 31 * result + surfaceBytes.contentHashCode()
        return result
    }
}

/**
 * 创建虚拟显示器响应
 *
 * @param ok        是否成功
 * @param displayId 成功时 > 0；失败时 -1
 * @param error     失败时的错误描述（包含异常类名 + message），便于 App 端直接显示给用户
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
