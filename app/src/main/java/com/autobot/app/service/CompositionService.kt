package com.autobot.app.service

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.util.ShellExecutor

/**
 * 虚拟显示器合成服务
 *
 * 负责：
 * 1. 通过 Shizuku 调用系统 DisplayManager.createVirtualDisplay() 创建虚拟显示，
 *    将屏幕画面输出到 NativeCapturer 提供的 Surface
 * 2. 持有 NativeCapturer 并把预览 Surface 绑定/解绑
 * 3. 提供触摸事件注入接口（通过 input tap/swipe 命令模拟）
 *
 * 权限路径：
 *  - 不需要 MediaProjection 弹窗，不需要用户在运行时确认
 *  - 仅依赖 Shizuku 授权（shell uid 持有 MANAGE_DISPLAYS 系统级权限）
 *  - 调用 DisplayServiceShizuku.createVirtualDisplay() 完成创建
 *
 * 使用流程：
 *   1. 检查 ShizukuManager.isShizukuGranted() == true
 *   2. 调用 startVirtualDisplay()，内部 NativeCapturer.setupNativeCapturer() 拿 Surface
 *   3. DisplayServiceShizuku.createVirtualDisplay(name, w, h, dpi, surface)
 *   4. 销毁时调用 stopVirtualDisplay() 反向释放资源
 */
class CompositionService {

    companion object {
        private const val TAG = "CompositionService"

        // 虚拟显示器默认分辨率（竖屏）
        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 960
        const val DEFAULT_DPI = 240

        // 虚拟显示器名称（用于 dumpsys display 排查）
        private const val VIRTUAL_DISPLAY_NAME = "AutoBOT-VirtualDisplay"
    }

    private var capturer: NativeCapturer? = null
    private var displaySurface: Surface? = null

    /** 缓存的预览 Surface（重启 VD 后自动重新绑定，无需 UI 侧重新通知） */
    private var cachedPreviewSurface: Surface? = null

    // Shizuku 路径创建的虚拟显示器 handle（release 时使用）
    private var virtualDisplayHandle: DisplayServiceShizuku.VirtualDisplayHandle? = null

    // 虚拟显示器配置
    var width: Int = DEFAULT_WIDTH
        private set
    var height: Int = DEFAULT_HEIGHT
        private set

    /**
     * 当前虚拟显示器是否为横屏（宽 > 高）
     * 用于 UI 层判断预览比例与全屏 Activity 方向
     */
    val isLandscape: Boolean get() = width > height

    /**
     * 重启虚拟显示器并切换到新的分辨率（用于横竖屏切换）
     *
     * 流程：
     *   1. 记录当前预览 Surface（若已绑定）
     *   2. 停止旧 VD → 释放资源
     *   3. 用新宽高创建 VD
     *   4. 重新绑定预览 Surface
     *
     * @param newWidth  新宽度
     * @param newHeight 新高度
     * @return 成功返回新 Surface；失败返回 null（此时旧 VD 也已被释放以避免状态不一致）
     */
    fun restartVirtualDisplay(newWidth: Int, newHeight: Int): Surface? {
        // 参数与当前一致 → 无需重建，直接返回现有 surface
        if (newWidth == width && newHeight == height && capturer != null) {
            Log.i(TAG, "restartVirtualDisplay skipped: size unchanged (${newWidth}x${newHeight})")
            return displaySurface
        }

        Log.i(TAG, "Restarting VirtualDisplay: ${width}x${height} -> ${newWidth}x${newHeight}")

        // 1. 暂存当前预览 Surface（重启后自动重新绑定）
        val existingPreviewSurface = cachedPreviewSurface

        // 2. 先彻底释放旧 VD 与 Native 资源，避免占用 display slot / Surface
        stopVirtualDisplay()

        // 3. 用新尺寸启动 VD（走与 startVirtualDisplay 相同的 Shizuku 路径）
        val newSurface = startVirtualDisplay(newWidth, newHeight)
        if (newSurface == null) {
            Log.e(TAG, "restartVirtualDisplay: startVirtualDisplay failed at ${newWidth}x${newHeight}")
            return null
        }

        // 4. 如果之前有绑定预览，自动重新绑定到新的 NativeCapturer
        if (existingPreviewSurface != null && existingPreviewSurface.isValid) {
            attachPreviewSurface(existingPreviewSurface)
            Log.i(TAG, "restartVirtualDisplay: re-attached previous preview surface")
        }

        return newSurface
    }

    /**
     * 虚拟显示器的 Display ID
     *
     * - 创建成功后通过反射从 VirtualDisplay.getDisplay().getDisplayId() 获取
     * - 用途：`am start --display <displayId>` 让目标 App 启动到此虚拟显示器上
     *   （而不是默认显示器/前台）
     * - 未启动时返回 -1
     */
    val displayId: Int get() = virtualDisplayHandle?.displayId ?: -1

    /**
     * 启动虚拟显示器（无需 MediaProjection，依赖 Shizuku shell 权限）
     *
     * 内部步骤：
     * 1. 校验 Shizuku 已授权（持有 MANAGE_DISPLAYS 系统级权限的前提）
     * 2. NativeCapturer.setupNativeCapturer(w, h) 拿到承载画面的 Surface
     * 3. DisplayServiceShizuku.createVirtualDisplay(name, w, h, dpi, surface)
     *    通过 ShizukuBinderWrapper 调用 IDisplayManager.createVirtualDisplay
     *
     * @return 虚拟显示器输出 Surface；失败返回 null
     */
    fun startVirtualDisplay(width: Int = DEFAULT_WIDTH,
                            height: Int = DEFAULT_HEIGHT): Surface? {
        if (capturer != null) {
            Log.w(TAG, "VirtualDisplay already running, stop first")
            return displaySurface
        }

        // 1. Shizuku 权限校验：未授权直接拒绝，避免后续反射调用 throw SecurityException
        if (!ShizukuManager.isShizukuGranted()) {
            Log.e(TAG, "Shizuku not granted, cannot create VirtualDisplay")
            return null
        }

        this.width = width
        this.height = height

        return try {
            // 2. 初始化 Native 层图像读取器，拿到承载画面的 Surface
            val cap = NativeCapturer()
            val surface = cap.setupNativeCapturer(width, height)
            if (surface == null) {
                Log.e(TAG, "setupNativeCapturer returned null surface")
                return null
            }
            capturer = cap
            displaySurface = surface

            // 3. 通过 Shizuku 调用 DisplayManager.createVirtualDisplay
            //    （不弹窗、不需要用户确认；shell uid 已持有 MANAGE_DISPLAYS 权限）
            val handle = DisplayServiceShizuku.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, width, height, DEFAULT_DPI, surface
            )
            if (handle == null) {
                Log.e(TAG, "DisplayServiceShizuku.createVirtualDisplay returned null")
                // 回滚已分配的 Native 资源
                cap.releaseNativeCapturer()
                capturer = null
                displaySurface = null
                return null
            }
            virtualDisplayHandle = handle

            Log.i(TAG, "VirtualDisplay started: ${width}x${height} (via Shizuku)")
            surface
        } catch (e: Exception) {
            Log.e(TAG, "startVirtualDisplay failed", e)
            // 异常回滚：防止资源泄漏
            try {
                virtualDisplayHandle?.release()
                capturer?.releaseNativeCapturer()
            } catch (_: Exception) {}
            virtualDisplayHandle = null
            capturer = null
            displaySurface = null
            null
        }
    }

    /**
     * 绑定预览 Surface（由 ViewModel 调用）
     * Surface 销毁时调用 detachPreviewSurface
     */
    fun attachPreviewSurface(surface: Surface?) {
        cachedPreviewSurface = surface
        capturer?.setPreviewSurface(surface)
        Log.i(TAG, "Preview surface attached: $surface")
    }

    /**
     * 解绑预览 Surface
     */
    fun detachPreviewSurface() {
        cachedPreviewSurface = null
        capturer?.setPreviewSurface(null)
        Log.i(TAG, "Preview surface detached")
    }

    /**
     * 注入触摸按下事件（虚拟显示器坐标）
     */
    fun injectTouchDown(x: Int, y: Int) {
        // 通过 input 命令注入（需要 Shizuku 或 root 权限）
        ShellExecutor.execute("input tap $x $y", useShizuku = true, timeout = 2000)
    }

    /**
     * 注入触摸移动事件
     */
    fun injectTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        // input swipe 的 duration 控制滑动速度
        ShellExecutor.execute(
            "input swipe $fromX $fromY $toX $toY 100",
            useShizuku = true,
            timeout = 2000
        )
    }

    /**
     * 注入触摸抬起事件（input 命令模型中 tap/swipe 自动结束，这里空实现）
     */
    fun injectTouchUp(x: Int, y: Int) {
        // input 命令本身是原子操作，无独立的 UP，可在此扩展 SendEvent 注入
    }

    /**
     * 获取当前帧 Bitmap 供识图引擎
     */
    fun getFrameBufferBitmap(): Bitmap? = capturer?.getFrameBufferBitmap()

    /**
     * 获取已捕获帧数
     */
    fun getFrameCount(): Long = capturer?.getFrameCount() ?: 0L

    /**
     * 停止虚拟显示器并释放所有资源（按创建顺序反向释放）
     * 释放顺序：VirtualDisplay → NativeCapturer
     */
    fun stopVirtualDisplay() {
        // 1. 先释放 VirtualDisplay（停止屏幕画面投射）
        try {
            virtualDisplayHandle?.release()
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay release failed", e)
        }
        virtualDisplayHandle = null

        // 2. 释放 Native 层资源（AImageReader / ANativeWindow）
        try {
            capturer?.releaseNativeCapturer()
        } catch (e: Exception) {
            Log.e(TAG, "releaseNativeCapturer failed", e)
        }
        capturer = null
        displaySurface = null

        Log.i(TAG, "VirtualDisplay stopped (all resources released)")
    }
}
