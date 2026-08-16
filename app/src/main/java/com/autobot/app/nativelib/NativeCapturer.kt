package com.autobot.app.nativelib

import android.graphics.Bitmap
import android.view.Surface

/**
 * 虚拟显示器帧捕获 JNI 绑定
 *
 * 由 native_capturer.cpp 通过 RegisterNatives 注册实现：
 *  - setupNativeCapturer(width, height): 创建 AImageReader 并返回虚拟显示器输出 Surface
 *  - releaseNativeCapturer(): 释放所有 Native 资源
 *  - setPreviewSurface(surface): 绑定/解绑预览 Surface
 *  - getFrameBufferBitmap(): 取出最近一帧用于识图
 *  - getFrameCount(): 获取已捕获帧数
 *
 * 注意：调用方需保证 setup/release 的成对使用，避免内存泄漏
 */
class NativeCapturer {

    /**
     * 初始化 Native 捕获器，返回虚拟显示器画面输出 Surface
     * @param width  虚拟显示器宽度（像素）
     * @param height 虚拟显示器高度（像素）
     * @return 用于绑定到 VirtualDisplay 的 Surface；失败返回 null
     */
    external fun setupNativeCapturer(width: Int, height: Int): Surface?

    /**
     * 释放 Native 资源：
     * 严格调用 AImage_delete / AImageReader_delete / ReleaseFrameBuffers
     */
    external fun releaseNativeCapturer()

    /**
     * 绑定 / 解绑预览 Surface
     * @param surface Compose 层 SurfaceView 提供的 Surface；传 null 表示解绑
     */
    external fun setPreviewSurface(surface: Surface?)

    /**
     * 取出当前帧缓冲为 Android Bitmap（ARGB_8888）供识图引擎使用
     * @return 当前帧 Bitmap；尚未捕获到帧时返回 null
     */
    external fun getFrameBufferBitmap(): Bitmap?

    /**
     * 获取已捕获的总帧数
     */
    external fun getFrameCount(): Long

    companion object {
        /**
         * 显式加载 Native 库
         * 建议在 Application/Activity 启动时调用一次
         */
        fun loadLibrary() {
            try {
                System.loadLibrary("autobot_native")
            } catch (e: UnsatisfiedLinkError) {
                // ABI 不匹配或缺少 .so 时会抛出
                android.util.Log.e("NativeCapturer", "loadLibrary failed", e)
                throw e
            }
        }
    }
}
