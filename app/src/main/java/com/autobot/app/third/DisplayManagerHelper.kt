package com.autobot.app.third

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * 通过 FakeContext 创建虚拟显示器（简化版，去除 ShizukuBinderWrapper 路径 A）。
 *
 * 架构变更：本 Helper 现在只在 **server 进程（Shizuku.newProcess 启动的 app_process，shell uid）**
 * 中被调用。因为 server 进程本身就是 shell uid，调用 DisplayManager.createVirtualDisplay()
 * 时系统侧 `Binder.getCallingUid() == SHELL_UID` 校验自然通过，**不需要** 再用
 * ShizukuBinderWrapper 包装 display binder、也不需要替换 DisplayManagerGlobal.sInstance。
 *
 * 新调用路径（ServerMain.handleCreateVd）：
 *   1. Workarounds.apply() —— App进程不需要，但 server 进程是 app_process 必须补 ActivityThread
 *   2. FakeContext.get() —— 返回 com.android.shell 身份的 Context
 *   3. 反射 DisplayManager(Context) 包私有构造器实例化 DisplayManager
 *   4. 反射 createVirtualDisplay(name, w, h, dpi, surface, flags) 公开 API
 *
 * 旧 App 进程内调用方式已废弃（ShizukuBinderWrapper + sInstance 替换在 App UID 下
 * 会被 Android 12+ system_server 拒绝；请改用 ServerMain + ShizukuProcessManager）。
 *
 * 注意：本文件不再持有 Context 字段或全局状态，是无状态工具类，线程安全。
 */
object DisplayManagerHelper {
    private const val TAG = "DisplayMgrHelper"

    /**
     * 兼容旧 CompositionService 调用，新架构下是 no-op。
     * server 进程内 ServerMain 直接调用 createVirtualDisplay(surface, ...) 即可。
     */
    @Synchronized
    fun init(baseContext: Context) {
        // 旧路径 A 已删除；server 进程无需初始化全局 sInstance
        Log.i(TAG, "init(): no-op (server-process architecture, no sInstance replacement needed)")
    }

    /**
     * 创建虚拟显示器（无 Context 参数版本，供 ServerMain 直接调用）。
     *
     * 内部使用 FakeContext.get()，FakeContext 自带 Workarounds.getSystemContext() 作为 base。
     *
     * @param surface VD 的画面输出 Surface（由 App 进程通过 Parcel 跨进程传递过来，
     *                在 server 进程内反序列化重建）
     * @return VirtualDisplay? 成功返回实例，失败返回 null（详见 logcat）
     */
    @SuppressLint("DiscouragedPrivateApi")
    fun createVirtualDisplay(
        surface: Surface,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        flags: Int
    ): VirtualDisplay? {
        return try {
            val fakeContext = FakeContext.get()
            Log.i(TAG, "new DisplayManager(FakeContext)...")
            val dmClass = android.hardware.display.DisplayManager::class.java
            val constructor = dmClass.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            val dm = constructor.newInstance(fakeContext)

            // 6 参数公开 API：createVirtualDisplay(name, w, h, dpi, surface, flags)
            val method = dmClass.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "calling DisplayManager.createVirtualDisplay(\"$name\", ${width}x${height}, dpi=$density, flags=0x${flags.toString(16)})...")
            val vd = method.invoke(dm, name, width, height, density, surface, flags) as? VirtualDisplay
            if (vd != null) {
                val displayId = vd.display?.displayId ?: -1
                Log.i(TAG, "✅ VirtualDisplay created: name=$name, ${width}x${height}, displayId=$displayId")
            } else {
                Log.e(TAG, "createVirtualDisplay returned null (no exception; check logcat for system_server errors)")
            }
            vd
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay exception: ${e.javaClass.simpleName}: ${e.message}", e)
            // 把根因挖出来，常见 SecurityException "packageName must match the calling uid"
            var cause: Throwable? = e
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            if (cause != null && cause !== e) {
                Log.e(TAG, "Root cause: ${cause.javaClass.simpleName}: ${cause.message}")
            }
            null
        }
    }

    /**
     * 兼容旧 CompositionService 旧 API（context 被忽略，FakeContext 自带 base）。
     * 新代码请用 6 参数版本。
     */
    fun createVirtualDisplay(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        flags: Int
    ): VirtualDisplay? {
        // context 参数在新架构下无意义（server 进程内调用，FakeContext 自带 base）
        return createVirtualDisplay(surface, name, width, height, density, flags)
    }

    /**
     * 构建虚拟显示器标志位（对齐 MAA-Meow buildDisplayFlags）。
     */
    fun buildDisplayFlags(): Int {
        var flags = (
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            or (1 shl 6)  // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (hidden constant)
        )
        // API 33+（Android 13 Tiramisu）新增标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or (
                (1 shl 8)   // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                or (1 shl 10)  // VIRTUAL_DISPLAY_FLAG_TRUSTED
                or (1 shl 11)  // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                or (1 shl 12)  // VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                or (1 shl 13)  // VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            )
            // API 34+（Android 14 UpsideDownCake）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or (
                    (1 shl 14)  // VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                    or (1 shl 15)  // VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                    or (1 shl 16)  // VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
                )
            }
        }
        Log.i(TAG, "buildDisplayFlags: SDK=${Build.VERSION.SDK_INT}, flags=0x${flags.toString(16)}")
        return flags
    }
}
