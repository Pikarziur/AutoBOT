package com.autobot.app.service

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.third.DisplayManagerHelper
import com.autobot.app.third.Workarounds
import com.autobot.app.util.ShellExecutor

/**
 * 虚拟显示器合成服务
 *
 * 核心改进（完全对齐 MAA-Meow + scrcpy 架构）：
 *  1. 前置 Workarounds.apply()：注入 ActivityThread、mBoundApplication(com.android.shell)、
 *     ConfigurationController(Android 12+)、mInitialApplication。没有这一步，
 *     DisplayManager(Context) 构造器内部在三星/小米等设备会 NPE。
 *  2. FakeContext 的 base 改为 Workarounds.getSystemContext()（系统级 Context），
 *     不再使用 App 的 applicationContext。
 *  3. DisplayManagerHelper 支持两条路径 fallback：
 *     · 路径 A：ShizukuBinderWrapper 包装 binder + 替换 sInstance（推荐）
 *     · 路径 B：纯 FakeContext 不修改全局（MAA-Meow 原始方式，兜底）
 *  4. 错误消息根据 logcat 根因进行分级，而不是笼统的"创建失败"。
 *
 * 权限路径：
 *  - 仅依赖 Shizuku 授权（shell uid 持有 MANAGE_DISPLAYS 系统级权限）
 *  - 不需要 MediaProjection 弹窗，不需要用户在运行时确认
 */
class CompositionService(private val context: Context) {

    companion object {
        private const val TAG = "CompositionSvc"

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
     * 步骤（新增 Workarounds 前置步骤）：
     * 0. Workarounds.apply() → FakeContext.get() 准备系统环境（关键！）
     * 1. 校验 Shizuku 已授权
     * 2. NativeCapturer.setupNativeCapturer(w, h) 拿到 Surface
     * 3. DisplayManagerHelper.init(context) 路径 A：尝试替换 sInstance
     * 4. DisplayManagerHelper.createVirtualDisplay → 路径 A 失败自动降级路径 B
     */
    fun startVirtualDisplay(width: Int = DEFAULT_WIDTH,
                            height: Int = DEFAULT_HEIGHT): Pair<Surface?, String> {
        if (virtualDisplay != null) {
            Log.w(TAG, "VirtualDisplay already running, stop first")
            return displaySurface to ""
        }

        // 步骤 0：最最最先准备 ActivityThread 系统环境（否则 FakeContext.getSystemContext() 拿不到）
        try {
            Workarounds.apply()
            Log.i(TAG, "step0 Workarounds.apply() OK")
        } catch (t: Throwable) {
            val msg = "系统环境准备失败: ${t.javaClass.simpleName}: ${t.message} (详见 logcat Workarounds 标签)"
            Log.e(TAG, "step0 FAIL: $msg", t)
            return null to msg
        }

        // 1. Shizuku 权限校验（详细诊断，不再笼统"未授权"）
        val diag = ShizukuManager.diagnoseShizuku(context)
        when (diag) {
            ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED -> {
                val msg = "Shizuku 未安装：请先安装 Shizuku App（rikka.shizuku / moe.shizuku.privileged.api）"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> {
                val msg = "Shizuku 未连接：请打开 Shizuku App 并通过「无线调试」或「ADB 命令」启动服务。" +
                        "注意：用 Root 模式启动 Shizuku 在 Android 12+ 上会触发" +
                        "\"packageName must match the calling uid\" 的 SecurityException，" +
                        "必须用 ADB/无线调试 模式启动 Shizuku（参考 MAA-Meow issue #9）。"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> {
                val msg = "Shizuku 已连接但未授权：请在设置页面点击「授权 Shizuku」按钮，" +
                        "或在 Shizuku App 的「已授权的应用」中手动添加本应用。"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR -> {
                val msg = "Shizuku 状态异常：请重启 Shizuku 服务后重试"
                Log.e(TAG, msg); return null to msg
            }
            ShizukuManager.ShizukuDiagnosis.OK -> {
                Log.i(TAG, "step1 Shizuku OK")
            }
        }

        this.width = width
        this.height = height

        return try {
            // 2. 初始化 Native 层图像读取器，拿到承载画面的 Surface
            Log.i(TAG, "step2 setupNativeCapturer(${width}x${height}) ...")
            val cap = NativeCapturer()
            val surface = cap.setupNativeCapturer(width, height)
            if (surface == null) {
                val msg = "Native 图像采集器初始化失败：setupNativeCapturer 返回 null。" +
                        "排查：①minSdkVersion 是否 >= 26（AImageReader 要求） ②libautobot_capturer.so 是否被正确打包进 APK" +
                        " ③Surface 纹理缓冲格式是否被此机型 GPU 支持（GL_TEXTURE_EXTERNAL_OES）"
                Log.e(TAG, "step2 FAIL: $msg")
                return null to msg
            }
            capturer = cap
            displaySurface = surface
            Log.i(TAG, "step2 OK: surface=$surface")

            // 3 & 4. DisplayManagerHelper 内部会走 A → B fallback 路径
            Log.i(TAG, "step3 DisplayManagerHelper.init() ...")
            DisplayManagerHelper.init(context)

            Log.i(TAG, "step4 DisplayManagerHelper.createVirtualDisplay() ...")
            val flags = DisplayManagerHelper.buildDisplayFlags()
            val vd = DisplayManagerHelper.createVirtualDisplay(
                context, VIRTUAL_DISPLAY_NAME, width, height, DEFAULT_DPI, surface, flags
            )

            if (vd == null) {
                val msg = "虚拟显示器创建失败。系统侧（DisplayManagerService / system_server）返回了空或抛出异常。\n" +
                        "请按以下顺序排查：\n" +
                        "  ① 打开 logcat 筛选标签 DisplayMgrHelper / CompositionSvc / DisplayManagerService，\n" +
                        "     查看 Path A 在哪一步失败（通常是 step6 清除 FINAL 字段失败）\n" +
                        "     以及 Path B 抛出的具体 Exception 类型和 message。\n" +
                        "  ② 若看到 SecurityException: \"packageName must match the calling uid\"，\n" +
                        "     说明你是用 Root 模式启动的 Shizuku，请改回「无线调试 / ADB」模式启动。\n" +
                        "  ③ 若看到 IllegalStateException: \"Need MANAGE_DISPLAYS permission\"，\n" +
                        "     说明 Shizuku 授权后 shell UID 仍被 ROM 限制（部分华为/荣耀/OPPO 定制系统），\n" +
                        "     可尝试在 Shizuku App 开启「ADB 安全设置」或升级 Shizuku 到最新版。\n" +
                        "  ④ 若看到 \"no createVirtualDisplay method found\" / \"NotSuchMethod\"，\n" +
                        "     说明此机型的 DisplayManager 包私有构造器被 ROM 改写；\n" +
                        "     未来可考虑升级为 Shizuku.newProcess() 方式（完全照搬 MAA-Meow 的 scrcpy 服务架构）。"
                Log.e(TAG, "step4 FAIL: $msg")
                cap.releaseNativeCapturer()
                capturer = null
                displaySurface = null
                return null to msg
            }
            virtualDisplay = vd

            Log.i(TAG, "✅ VirtualDisplay started: ${width}x${height} displayId=${vd.display?.displayId}")
            surface to ""
        } catch (e: Exception) {
            var cause: Throwable? = e
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            val detail = if (cause != null && cause !== e) {
                "（根因：${cause.javaClass.simpleName}: ${cause.message}）"
            } else ""
            val msg = "虚拟显示器启动异常: ${e.javaClass.simpleName}: ${e.message} $detail"
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
