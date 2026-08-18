package com.autobot.app.nativelib

import android.graphics.Bitmap
import android.view.Surface

/**
 * Native 图像采集器（App 进程内运行，已改造为"帧注入模式"）
 *
 * 旧架构（App 进程内创建 VD）：
 *   setupNativeCapturer → 创建 AImageReader + 返回 Surface → Surface 给 DisplayManager
 *   onImageAvailable（native 层 AImageReader 回调）→ 读帧 → 写入 frameBuffer
 *   → setPreviewSurface 时把 frameBuffer blit 到 SurfaceView
 *   → getFrameBufferBitmap / getFrameCount 暴露给 UI 和识图引擎
 *
 * 新架构（server 进程 shell uid 创建 VD，JPEG 帧通过 socket 传 App）：
 *   setupNativeCapturer —— 仍保留但 fallback 用，新代码调用 prepareFrameBuffer
 *   prepareFrameBuffer(w,h) —— 仅分配指定大小的 frameBuffer（不再创建 AImageReader/ANativeWindow）
 *   injectExternalFrame(bitmap) —— 新方法，把 Bitmap 像素写入 frameBuffer + 自增 frameCount
 *                                → 若 previewSurface 已设置则调用 blitPreview() 刷新预览
 *   setPreviewSurface / getFrameBufferBitmap / getFrameCount / releaseNativeCapturer —— 完全不变
 *
 * MonitorViewModel / TasksScreen 完全兼容（仍然调用 attachPreviewSurface、getFrameBufferBitmap 等）。
 */
class NativeCapturer {

    companion object {
        init {
            try {
                System.loadLibrary("autobot_native")
            } catch (t: Throwable) {
                android.util.Log.e("NativeCapturer", "loadLibrary autobot_native failed: ${t.message}")
            }
        }
    }

    /**
     * 旧方法：创建 AImageReader（创建 VD 的输入 Surface）并分配 frameBuffer。
     * 新架构下 server 端自己创建 VD + AImageReader，App 端不再需要。保留作为 fallback。
     * @return VD 输出端使用的 Surface（旧架构）；新架构下调用方应 prepareFrameBuffer 忽略返回值
     */
    external fun setupNativeCapturer(width: Int, height: Int): Surface?

    /**
     * 新方法：只分配指定大小的 frameBuffer（不创建 AImageReader / ANativeWindow / Surface）。
     * 新架构下 CompositionService 应调用此方法（节省 AImageReader 资源）。
     *
     * @return true 分配成功；false 分配失败（out of memory 等）
     */
    external fun prepareFrameBuffer(width: Int, height: Int): Boolean

    /**
     * 新方法：把 server 端通过 MSG_FRAME 发来、App 端解码后的 Bitmap 像素写入 native 端的 frameBuffer，
     *        若 previewSurface 已设置则同时 blit 到 SurfaceView 更新预览。
     *
     * 内部会自增 frameCount（getFrameCount 会同步增长）。
     *
     * @param bitmap 推荐尺寸与 createVD 时的 w/h 完全一致（540x960 或 960x540）；
     *               如不一致内部自动按最近尺寸裁剪/缩放（尽量保持宽高比）
     */
    external fun injectExternalFrame(bitmap: Bitmap)

    /**
     * 设置预览 Surface（如果存在的话会把每一帧绘制到此 Surface 上，实现 UI 预览画面）
     * 新架构下 injectExternalFrame 内部会在写完 frameBuffer 后自动 blitPreview 到这里。
     */
    external fun setPreviewSurface(surface: Surface?)

    /** 获取当前 frameBuffer 的 Bitmap（用于图色识别引擎） */
    external fun getFrameBufferBitmap(): Bitmap?

    /** 当前帧计数（每收到一帧 injectExternalFrame 或 onImageAvailable +1） */
    external fun getFrameCount(): Long

    /** 释放 native 端资源（AImageReader、ANativeWindow、frameBuffer、previewSurface） */
    external fun releaseNativeCapturer()
}
