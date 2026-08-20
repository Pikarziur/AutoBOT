package com.autobot.app.nativelib

import android.graphics.Bitmap
import android.view.Surface

/**
 * Native 图像采集器（App 进程内运行，已改造为"帧注入模式"）。
 *
 * 架构变更：旧架构 App 进程内创建 VD（setupNativeCapturer 创建 AImageReader + 返回 Surface）；
 * 新架构 server 进程创建 VD，JPEG 帧通过 socket 传 App，App 端调用 prepareFrameBuffer +
 * injectExternalFrame 把 Bitmap 像素写入 frameBuffer。setupNativeCapturer 保留作为 fallback。
 *
 * MonitorViewModel / TasksScreen 完全兼容（仍调用 attachPreviewSurface、getFrameBufferBitmap 等）。
 */
class NativeCapturer {

    companion object {
        init {
            // 类首次加载时自动尝试一次，兜底吞异常避免类加载失败
            try {
                loadLibrary()
            } catch (t: Throwable) {
                android.util.Log.e("NativeCapturer",
                    "companion init loadLibrary failed: ${t.message}")
            }
        }

        /**
         * 显式加载 native 库 autobot_native.so。
         * System.loadLibrary 对已加载的库幂等返回；本方法**不吞异常**，让调用方可通过
         * try-catch UnsatisfiedLinkError 在 UI 早暴露加载失败（companion init 已自行 try-catch 兜底）。
         */
        @JvmStatic
        fun loadLibrary() {
            System.loadLibrary("autobot_native")
        }
    }

    /**
     * 旧方法：创建 AImageReader（VD 的输入 Surface）并分配 frameBuffer。
     * 新架构下 server 端自建 VD + AImageReader，App 端不再需要；保留作为 fallback。
     */
    external fun setupNativeCapturer(width: Int, height: Int): Surface?

    /**
     * 新方法：只分配指定大小的 frameBuffer（不创建 AImageReader / ANativeWindow / Surface）。
     * 新架构下 CompositionService 应调用此方法（节省 AImageReader 资源）。
     */
    external fun prepareFrameBuffer(width: Int, height: Int): Boolean

    /**
     * 新方法：把 server 端 MSG_FRAME 发来、App 端解码后的 Bitmap 像素写入 native 端 frameBuffer，
     * 若 previewSurface 已设置则同时 blit 到 SurfaceView 更新预览；内部自增 frameCount。
     *
     * bitmap 推荐尺寸与 createVD 时的 w/h 完全一致；不一致时内部自动按最近尺寸裁剪/缩放。
     */
    external fun injectExternalFrame(bitmap: Bitmap)

    /**
     * 设置预览 Surface；新架构下 injectExternalFrame 写完 frameBuffer 后会自动 blitPreview 到这里。
     */
    external fun setPreviewSurface(surface: Surface?)

    /** 获取当前 frameBuffer 的 Bitmap（用于图色识别引擎）。 */
    external fun getFrameBufferBitmap(): Bitmap?

    /** 当前帧计数（每收到一帧 injectExternalFrame 或 onImageAvailable +1）。 */
    external fun getFrameCount(): Long

    /** 释放 native 端资源（AImageReader、ANativeWindow、frameBuffer、previewSurface）。 */
    external fun releaseNativeCapturer()
}
