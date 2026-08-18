package com.autobot.app.service

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.third.DisplayManagerHelper
import com.autobot.app.util.ShellExecutor

/**
 * 虚拟显示器合成服务
 *
 * 核心改进（参照 MAA-Meow）：
 * 1. 用 DisplayManagerHelper 替代 DisplayServiceShizuku
 *    - 通过 DisplayManager(Context) 包私有构造器 + FakeContext 实例化
 *    - 调用 DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags) 6 参重载
 *    - 绕过 MIUI 隐藏 API 过滤（用公开类公开方法，不走 IDisplayManager AIDL 接口反射）
 * 2. packageName 传 "com.android.shell"（FakeContext），AttributionSource 用 SHELL_UID
 * 3. 通过 ShizukuBinderWrapper 包装 DisplayManagerGlobal 的 binder，使 binder 调用走 shell uid
 *
 * 权限路径：
 *  - 仅依赖 Shizuku 授权（shell uid 持有 MANAGE_DISPLAYS 系统级权限）
 *  - 不需要 MediaProjection 弹窗，不需要用户在运行时确认
 */
class CompositionService(private val context: Context) {

    companion object {
        private const val TAG = "CompositionService"

        // 虚拟显示器默认分辨率（竖屏）
        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 960
        const val DEFAULT_DPI = 240

        // 虚拟显示器名称
        private const val VIRTUAL_DISPLAY_NAME = "AutoBOT-VirtualDisplay"
    }

    private var capturer: NativeCapturer? = null
    private var displaySurface: Surface? = null

    /** 缓存的预览 Surface（重启 VD 后自动重新绑定） */
    private var cachedPreviewSurface: Surface? = null

    /** VirtualDisplay 对象（release 时调用其 release()） */
    private var virtualDisplay: VirtualDisplay? = null

    var width: Int = DEFAULT_WIDTH
        private set
    var height: Int = DEFAULT_HEIGHT
        private set

    val isLandscape: Boolean get() = width > height

    /**
     * 虚拟显示器的 Display ID
     * 用于 am start --display <displayId> 让 App 启动到虚拟显示器
     */
    val displayId: Int get() = virtualDisplay?.display?.displayId ?: -1

    /**
     * 启动虚拟显示器
     *
     * 步骤：
     * 1. 校验 Shizuku 已授权
     * 2. NativeCapturer.setupNativeCapturer(w, h) 拿到 Surface
     * 3. DisplayManagerHelper.createVirtualDisplay(name, w, h, dpi, surface, flags)
     *    用 DisplayManager 公开 API + Shizuku 包装的 binder 创建虚拟显示器
     */
    fun startVirtualDisplay(width: Int = DEFAULT_WIDTH,
                            height: Int = DEFAULT_HEIGHT): Pair<Surface?, String> {
        if (virtualDisplay != null) {
            Log.w(TAG, "VirtualDisplay already running, stop first")
            return displaySurface to ""
        }

        // 1. Shizuku 权限校验
        if (!ShizukuManager.isShizukuConnected()) {
            val msg = "Shizuku 服务未连接，请打开 Shizuku App 并通过 ADB 启动服务"
            Log.e(TAG, msg)
            return null to msg
        }
        if (!ShizukuManager.isShizukuGranted()) {
            val msg = "Shizuku 未授权，请在设置页面点击授权按钮"
            Log.e(TAG, msg)
            return null to msg
        }

        this.width = width
        this.height = height

        return try {
            // 2. 初始化 Native 层图像读取器，拿到承载画面的 Surface
            val cap = NativeCapturer()
            val surface = cap.setupNativeCapturer(width, height)
            if (surface == null) {
                val msg = "Native 图像采集器初始化失败，请确认 minSdkVersion>=26 且 so 库已加载"
                Log.e(TAG, msg)
                return null to msg
            }
            capturer = cap
            displaySurface = surface

            // 3. 初始化 DisplayManagerHelper（替换 DisplayManagerGlobal 单例为 Shizuku 包装版本）
            DisplayManagerHelper.init(context)

            // 4. 用 DisplayManager 公开 API 创建虚拟显示器
            val flags = DisplayManagerHelper.buildDisplayFlags()
            val vd = DisplayManagerHelper.createVirtualDisplay(
                context, VIRTUAL_DISPLAY_NAME, width, height, DEFAULT_DPI, surface, flags
            )

            if (vd == null) {
                val msg = "虚拟显示器创建失败。可能原因：①Shizuku binder 包装失败 ②ROM 限制 ③槽位已满"
                Log.e(TAG, msg)
                cap.releaseNativeCapturer()
                capturer = null
                displaySurface = null
                return null to msg
            }
            virtualDisplay = vd

            Log.i(TAG, "VirtualDisplay started: ${width}x${height} displayId=${vd.display?.displayId}")
            surface to ""
        } catch (e: Exception) {
            val msg = "虚拟显示器启动异常: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            try {
                virtualDisplay?.release()
                capturer?.releaseNativeCapturer()
            } catch (_: Exception) {}
            virtualDisplay = null
            capturer = null
            displaySurface = null
            null to msg
        }
    }

    /**
     * 重启虚拟显示器并切换分辨率（横竖屏切换）
     */
    fun restartVirtualDisplay(newWidth: Int, newHeight: Int): Pair<Surface?, String> {
        if (newWidth == width && newHeight == height && virtualDisplay != null) {
            Log.i(TAG, "restartVirtualDisplay skipped: size unchanged")
            return displaySurface to ""
        }

        Log.i(TAG, "Restarting VirtualDisplay: ${width}x${height} -> ${newWidth}x${newHeight}")
        val existingPreviewSurface = cachedPreviewSurface
        stopVirtualDisplay()

        val (newSurface, err) = startVirtualDisplay(newWidth, newHeight)
        if (newSurface == null) {
            Log.e(TAG, "restartVirtualDisplay failed: $err")
            return null to err
        }

        if (existingPreviewSurface != null && existingPreviewSurface.isValid) {
            attachPreviewSurface(existingPreviewSurface)
            Log.i(TAG, "restartVirtualDisplay: re-attached preview surface")
        }

        return newSurface to ""
    }

    fun attachPreviewSurface(surface: Surface?) {
        cachedPreviewSurface = surface
        capturer?.setPreviewSurface(surface)
        Log.i(TAG, "Preview surface attached: $surface")
    }

    fun detachPreviewSurface() {
        cachedPreviewSurface = null
        capturer?.setPreviewSurface(null)
        Log.i(TAG, "Preview surface detached")
    }

    fun injectTouchDown(x: Int, y: Int) {
        ShellExecutor.execute("input tap $x $y", useShizuku = true, timeout = 2000)
    }

    fun injectTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        ShellExecutor.execute("input swipe $fromX $fromY $toX $toY 100", useShizuku = true, timeout = 2000)
    }

    fun injectTouchUp(x: Int, y: Int) {}

    fun getFrameBufferBitmap(): Bitmap? = capturer?.getFrameBufferBitmap()

    fun getFrameCount(): Long = capturer?.getFrameCount() ?: 0L

    fun stopVirtualDisplay() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay release failed", e)
        }
        virtualDisplay = null

        try {
            capturer?.releaseNativeCapturer()
        } catch (e: Exception) {
            Log.e(TAG, "releaseNativeCapturer failed", e)
        }
        capturer = null
        displaySurface = null

        Log.i(TAG, "VirtualDisplay stopped")
    }
}
