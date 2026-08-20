package com.autobot.app.third

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * 通过 FakeContext 创建虚拟显示器（server 进程 shell uid 中调用）。
 *
 * 踩坑：旧 App 进程内 ShizukuBinderWrapper + sInstance 替换方案在 App UID 下会被
 * Android 12+ system_server 拒绝；新架构 server 进程本身就是 shell uid，直接调
 * createVirtualDisplay 即可通过 `Binder.getCallingUid() == SHELL_UID` 校验，
 * 不再需要 ShizukuBinderWrapper 包装或替换 DisplayManagerGlobal.sInstance。
 *
 * 调用顺序：Workarounds.apply() → FakeContext.get() → 反射 DisplayManager(Context)
 * 包私有构造器 → 反射 createVirtualDisplay(name, w, h, dpi, surface, flags)。
 *
 * 无状态工具类，线程安全。
 */
object DisplayManagerHelper {
    private const val TAG = "DisplayMgrHelper"

    /** 兼容旧 CompositionService 调用，新架构下是 no-op。 */
    @Synchronized
    fun init(baseContext: Context) {
        Log.i(TAG, "init(): no-op (server-process architecture, no sInstance replacement needed)")
    }

    /**
     * 创建虚拟显示器（无 Context 参数版本，供 ServerMain 直接调用）。
     * 内部使用 FakeContext.get()，FakeContext 自带 Workarounds.getSystemContext() 作为 base。
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
            var cause: Throwable? = e
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            if (cause != null && cause !== e) {
                Log.e(TAG, "Root cause: ${cause.javaClass.simpleName}: ${cause.message}")
            }
            null
        }
    }

    /** 兼容旧 CompositionService 旧 API（context 被忽略，FakeContext 自带 base）。 */
    fun createVirtualDisplay(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        flags: Int
    ): VirtualDisplay? {
        return createVirtualDisplay(surface, name, width, height, density, flags)
    }

    /** 构建虚拟显示器标志位（对齐 MAA-Meow buildDisplayFlags）。 */
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
