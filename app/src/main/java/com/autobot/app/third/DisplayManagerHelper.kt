package com.autobot.app.third

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager asSystemServiceManager
import android.util.Log
import android.view.Surface
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 通过 Shizuku 创建虚拟显示器的核心 Helper。
 *
 * 原理（参照 MAA-Meow 的 DisplayManager wrapper）：
 * 1. 反射 ServiceManager.getService("display") 拿到原始 IBinder
 * 2. 用 ShizukuBinderWrapper 包装，使 binder.transact() 走 Shizuku（shell uid）
 * 3. 反射 DisplayManagerGlobal 构造器，用包装后的 IDisplayManager 实例化
 * 4. 反射替换 DisplayManagerGlobal.sInstance 单例
 * 5. 反射 DisplayManager 包私有构造器 (Context)，用 FakeContext 实例化
 * 6. 调用 DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags) 6 参重载
 *
 * 关键突破：用 DisplayManager（公开类）的公开方法 createVirtualDisplay，
 * 而非 IDisplayManager（AIDL 隐藏接口）的 createVirtualDisplay，绕过 MIUI 隐藏 API 过滤。
 */
object DisplayManagerHelper {
    private const val TAG = "DisplayManagerHelper"

    @Volatile
    private var initialized = false

    /**
     * 初始化：替换 DisplayManagerGlobal 单例为 Shizuku 包装版本。
     * 只需执行一次（进程级缓存）。
     */
    @Synchronized
    fun init(baseContext: Context) {
        if (initialized) return

        try {
            // 1. 反射 ServiceManager.getService("display")
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val displayBinder = getService.invoke(null, "display") as? IBinder
                ?: run {
                    Log.e(TAG, "ServiceManager.getService(\"display\") returned null")
                    return
                }

            // 2. ShizukuBinderWrapper 包装
            val wrappedBinder = ShizukuBinderWrapper(displayBinder)

            // 3. 反射 IDisplayManager.Stub.asInterface(wrappedBinder)
            val iDisplayManagerStubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterface = iDisplayManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val iDisplayManager = asInterface.invoke(null, wrappedBinder)
                ?: run {
                    Log.e(TAG, "IDisplayManager.Stub.asInterface returned null")
                    return
                }

            // 4. 反射 DisplayManagerGlobal 构造器，用包装后的 iDisplayManager 实例化
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmgConstructor = dmgClass.getDeclaredConstructor(iDisplayManager::class.java)
            dmgConstructor.isAccessible = true
            val dmgInstance = dmgConstructor.newInstance(iDisplayManager)

            // 5. 反射替换 DisplayManagerGlobal.sInstance 单例
            //    字段名可能是 sInstance 或 sInstance，需要反射查找
            val sInstanceField = try {
                dmgClass.getDeclaredField("sInstance")
            } catch (e: NoSuchFieldException) {
                // 尝试其他可能的字段名
                dmgClass.declaredFields.firstOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    java.lang.reflect.Modifier.isPrivate(it.modifiers) &&
                    it.type == dmgClass
                } ?: throw e
            }
            sInstanceField.isAccessible = true

            // 如果字段是 final 的，需要清除 modifier
            val modifiers = java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
            modifiers.isAccessible = true
            val currentModifiers = sInstanceField.modifiers
            if (java.lang.reflect.Modifier.isFinal(currentModifiers)) {
                modifiers.setInt(sInstanceField, currentModifiers and java.lang.reflect.Modifier.FINAL.inv())
            }
            sInstanceField.set(null, dmgInstance)

            initialized = true
            Log.i(TAG, "DisplayManagerGlobal sInstance replaced with Shizuku-wrapped binder")
        } catch (e: Exception) {
            Log.e(TAG, "init failed, falling back to default DisplayManagerGlobal", e)
            // 失败也不阻止，后续 createVirtualDisplay 会用默认的 DisplayManagerGlobal
            // 只是 binder 调用不走 Shizuku，可能权限不足
        }
    }

    /**
     * 创建虚拟显示器。
     *
     * 照搬 MAA-Meow 的 DisplayManager.createNewVirtualDisplay：
     * - 反射 DisplayManager 的包私有构造器 (Context)，用 FakeContext 实例化
     * - 调用 createVirtualDisplay(name, w, h, dpi, surface, flags) 6 参重载
     *
     * @param name    虚拟显示器名称
     * @param width   宽度（像素）
     * @param height  高度（像素）
     * @param density DPI
     * @param surface 画面输出目标 Surface
     * @param flags   虚拟显示器标志位
     * @return 成功返回 VirtualDisplay；失败返回 null
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
        if (!initialized) init(context)

        return try {
            // 确保 FakeContext 初始化
            val fakeContext = FakeContext.get(context)

            // 反射 DisplayManager 的包私有构造器 (Context)
            val dmClass = android.hardware.display.DisplayManager::class.java
            val constructor = dmClass.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            val dm = constructor.newInstance(fakeContext)

            // 调用 createVirtualDisplay(name, w, h, dpi, surface, flags) 6 参重载
            // 这是公开 API（API 17+），虽然 API 33+ deprecated 但仍然可用
            val method = dmClass.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType
            )
            val vd = method.invoke(dm, name, width, height, density, surface, flags) as? VirtualDisplay

            if (vd != null) {
                val displayId = vd.display?.displayId ?: -1
                Log.i(TAG, "VirtualDisplay created: $name ${width}x${height} displayId=$displayId")
            } else {
                Log.e(TAG, "createVirtualDisplay returned null")
            }
            vd
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
    }

    /**
     * 构建虚拟显示器标志位（参照 MAA-Meow 的 buildDisplayFlags）
     */
    fun buildDisplayFlags(): Int {
        var flags = (
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            or (1 shl 6)  // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        )
        // API 33+ 额外标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or (
                (1 shl 8)   // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                or (1 shl 10)  // VIRTUAL_DISPLAY_FLAG_TRUSTED
                or (1 shl 11)  // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                or (1 shl 12)  // VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                or (1 shl 13)  // VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or (
                    (1 shl 14)  // VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                    or (1 shl 15)  // VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                    or (1 shl 16)  // VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
                )
            }
        }
        return flags
    }
}
