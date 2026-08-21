package com.autobot.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.ArrayDeque

/**
 * Bitmap 复用池：基于 [BitmapFactory.Options.inBitmap] 复用底层像素内存，避免每帧重新分配。
 *
 * 适用场景：VD 抓图 → JPEG 解码 → 识别 → recycle 的循环；同一分辨率下复用同一块 native 像素内存。
 *
 * 注意：
 * - 从 KitKat (API 19) 起 inBitmap 支持等大或更大的解码目标；
 * - inSampleSize = 1 时要求池中 Bitmap 尺寸 >= 解码目标尺寸；
 * - inPreferredConfig 必须与池中 Bitmap 配置一致（默认 ARGB_8888）。
 */
object BitmapPool {

    private const val TAG = "BitmapPool"

    /** 池容量：保留 4 块就够 30fps 流水线（生产+消费+1 缓冲）。 */
    private const val MAX_POOL_SIZE = 4

    private val pool = ArrayDeque<Bitmap>()

    private val lock = Any()

    /**
     * 申请一块复用的 Bitmap（解码 JPEG / RGBA 之前调用）。
     * 若池中有可复用项返回；否则返回 null 让调用方新建。
     */
    fun acquire(): Bitmap? = synchronized(lock) {
        pool.pollFirst()?.also {
            Log.v(TAG, "acquire: reused pooled bitmap, remaining=${pool.size}")
        }
    }

    /**
     * 归还 Bitmap 到池中供下次复用。
     * 不直接 recycle：因为 inBitmap 复用要求 bitmap 仍可写（mNativePtr 有效）。
     * 调用方应保证归还的 bitmap 不再被其他对象持有。
     */
    fun release(bitmap: Bitmap?) {
        if (bitmap == null) return
        if (bitmap.isRecycled) return
        synchronized(lock) {
            if (pool.size < MAX_POOL_SIZE && !pool.contains(bitmap)) {
                pool.addLast(bitmap)
                Log.v(TAG, "release: returned to pool, size=${pool.size}")
            } else {
                bitmap.recycle()
                Log.v(TAG, "release: pool full, recycled")
            }
        }
    }

    /**
     * 配置 BitmapFactory.Options：若池中有可复用 Bitmap，设置 inBitmap；否则按需 inPreferredConfig。
     * 调用方解码失败时（BitmapFactory 抛出或返回 null）应清空 inBitmap 重试一次。
     */
    fun applyToOptions(opts: BitmapFactory.Options) {
        synchronized(lock) {
            val pooled = pool.peekFirst()
            if (pooled != null && !pooled.isRecycled) {
                opts.inBitmap = pooled
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                opts.inSampleSize = 1
                opts.inMutable = true
            } else {
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        }
    }

    /** 清空池：用于 VD 重启或退出时释放所有 native 像素内存。 */
    fun clear() {
        synchronized(lock) {
            while (pool.isNotEmpty()) {
                val b = pool.pollFirst()
                if (b != null && !b.isRecycled) b.recycle()
            }
        }
        Log.i(TAG, "pool cleared")
    }
}
