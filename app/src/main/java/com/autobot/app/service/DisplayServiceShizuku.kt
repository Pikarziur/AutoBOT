package com.autobot.app.service

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import android.view.Surface
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 通过 Shizuku 调用系统 DisplayManager 创建虚拟显示器的 Helper
 *
 * 原理：
 * 1. 通过反射 ServiceManager.getService("display") 拿到原始 IBinder
 * 2. 用 ShizukuBinderWrapper 包装：使后续 binder.transact() 通过 Shizuku 服务转发，
 *    实际调用方 UID 为 shell (2000)，持有 MANAGE_DISPLAYS 系统级权限
 * 3. 反射 IDisplayManager.Stub.asInterface(wrappedBinder) 拿到 IDisplayManager 实例
 * 4. 反射调用 createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, String)
 *
 * 全程不需要 MediaProjection 弹窗、不需要用户确认；前置条件仅为 Shizuku 已授权。
 *
 * 适配说明：
 *  - VirtualDisplayConfig 在 API 33 引入，对应 IDisplayManager.createVirtualDisplay
 *    的 4 参数重载；API 33 以下使用旧的 8 参数重载
 *  - IVirtualDisplayCallback 是抽象 AIDL 接口，使用 Proxy 动态代理构造空实现
 */
object DisplayServiceShizuku {

    private const val TAG = "DisplayServiceShizuku"

    /**
     * 持有通过 Shizuku 包装的 IDisplayManager 及其反射元信息
     */
    private class DisplayManagerHandle(
        val iDisplayManager: Any,              // IDisplayManager 实例
        val createVirtualDisplayMethod: java.lang.reflect.Method
    )

    /**
     * 持有创建好的虚拟显示器引用（释放时调用其 release 反射方法）
     *
     * @param displayId 虚拟显示器的 Display ID
     *                  用法：`am start --display <displayId> -n pkg/cls` 可让 App 在此虚拟显示器上启动
     */
    class VirtualDisplayHandle(
        private val virtualDisplay: Any,
        private val releaseMethod: java.lang.reflect.Method,
        val displayId: Int
    ) {
        fun release() {
            try {
                releaseMethod.invoke(virtualDisplay)
            } catch (e: Exception) {
                Log.e(TAG, "VirtualDisplay.release failed", e)
            }
        }
    }

    /**
     * 获取（缓存）DisplayManagerHandle
     * 通过 ShizukuBinderWrapper 包装 display 服务 binder
     */
    @Volatile private var cachedHandle: DisplayManagerHandle? = null

    private fun getDisplayManagerHandle(): DisplayManagerHandle? {
        cachedHandle?.let { return it }

        return try {
            // 1. 反射 ServiceManager.getService("display")
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val displayBinder = getService.invoke(null, "display") as? IBinder
                ?: run {
                    Log.e(TAG, "ServiceManager.getService(\"display\") returned null")
                    return null
                }

            // 2. 用 ShizukuBinderWrapper 包装：binder 调用走 Shizuku 的 shell uid
            val wrappedBinder = ShizukuBinderWrapper(displayBinder)

            // 3. 反射 IDisplayManager.Stub.asInterface(wrappedBinder)
            val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val iDisplayManager = asInterface.invoke(null, wrappedBinder)
                ?: run {
                    Log.e(TAG, "IDisplayManager.Stub.asInterface returned null")
                    return null
                }

            // 4. 反射拿 createVirtualDisplay 方法（兼容各 Android 版本签名变化）
            //    API 33+ (Android 13+): createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, String, IBinder)
            //    API 28-32 (Android 9-12): createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, String)
            //    其他 ROM 定制变体：兜底遍历所有 createVirtualDisplay 重载
            //
            // 【MIUI / 隐藏 API 绕过修复】
            // 原先使用接口类 IDisplayManager.class 会被 MIUI 的隐藏 API 过滤机制截断（只能看到 getDisplayInfo 一个方法）。
            // 改用 iDisplayManager 的运行时类（IDisplayManager$Stub$Proxy，AIDL 自动生成的 Binder Proxy）
            // 再 fallback 到接口类。Proxy 是编译器生成类，不走隐藏 API 黑名单，方法齐全。
            val instanceClass: Class<*> = iDisplayManager.javaClass
            val interfaceClass = runCatching { Class.forName("android.hardware.display.IDisplayManager") }.getOrNull()
            var method = findCreateVirtualDisplayMethod(instanceClass)
            val searchedClass: Class<*>
            if (method != null) {
                searchedClass = instanceClass
            } else if (interfaceClass != null) {
                method = findCreateVirtualDisplayMethod(interfaceClass)
                searchedClass = interfaceClass
            } else {
                searchedClass = instanceClass
            }
            if (method == null) {
                Log.e(TAG, "No createVirtualDisplay method found. Scanned classes: instance=${instanceClass.name}, interface=${interfaceClass?.name}")
                Log.e(TAG, "Available methods on instance class (${instanceClass.name}):")
                instanceClass.declaredMethods.forEach { m ->
                    Log.w(TAG, "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
                return null
            }
            Log.i(TAG, "createVirtualDisplay signature: ${method.parameterTypes.joinToString { it.simpleName }}")

            val handle = DisplayManagerHandle(iDisplayManager, method)
            cachedHandle = handle
            Log.i(TAG, "DisplayManagerHandle created via Shizuku")
            handle
        } catch (e: Exception) {
            Log.e(TAG, "getDisplayManagerHandle failed", e)
            null
        }
    }

    /**
     * 查找 IDisplayManager.createVirtualDisplay 方法
     *
     * 兼容各 Android 版本的签名变化：
     *   - API 33+ (Android 13+): createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, String, IBinder)
     *   - API 28-32 (Android 9-12): createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, String)
     *   - 其他 ROM 定制变体：兜底取任意一个名为 createVirtualDisplay 的方法
     *
     * 关键修复：原代码第二个 getMethod 抛 NoSuchMethodException 未被捕获，导致整个流程失败。
     *          现在所有签名尝试均用 try-catch 包裹，最终兜底遍历所有方法。
     */
    private fun findCreateVirtualDisplayMethod(cls: Class<*>): java.lang.reflect.Method? {
        val virtualDisplayConfigClass = runCatching {
            Class.forName("android.hardware.display.VirtualDisplayConfig")
        }.getOrNull() ?: return null
        val callbackClass = runCatching {
            Class.forName("android.hardware.display.IVirtualDisplayCallback")
        }.getOrNull() ?: return null

        // 1. 尝试 4 参数签名（API 33+ 带 nativeToken / IBinder）
        try {
            return cls.getMethod(
                "createVirtualDisplay",
                virtualDisplayConfigClass,
                callbackClass,
                String::class.java,
                IBinder::class.java
            )
        } catch (_: NoSuchMethodException) { /* 继续尝试下一个签名 */ }

        // 2. 尝试 3 参数签名（API 28-32）
        try {
            return cls.getMethod(
                "createVirtualDisplay",
                virtualDisplayConfigClass,
                callbackClass,
                String::class.java
            )
        } catch (_: NoSuchMethodException) { /* 继续兜底 */ }

        // 3. 兜底：遍历所有声明的方法（含继承接口的），挑任意一个名为 createVirtualDisplay 的重载
        //    适配 ROM 定制或新版 Android 改了参数类型/顺序的情况
        Log.w(TAG, "Known signatures not matched, scanning all createVirtualDisplay overloads...")
        val allMethods = cls.declaredMethods.toList() +
            cls.interfaces.flatMap { it.declaredMethods.toList() }
        val candidate = allMethods.firstOrNull { it.name == "createVirtualDisplay" }
        if (candidate != null) {
            Log.w(TAG, "Fallback method found: createVirtualDisplay(${candidate.parameterTypes.joinToString { it.simpleName }})")
        }
        return candidate
    }

    /**
     * 构造 IVirtualDisplayCallback 的动态代理实例（空实现即可，回调由 NativeCapturer 持有的 Surface 自动消费）
     */
    private fun createCallbackProxy(): Any {
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        // 必须额外实现 IInterface 接口
        return java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass, IInterface::class.java)
        ) { _, method, _ ->
            // onDisplayStateChanged / onStopped 等回调可在此扩展，目前空实现
            when (method.name) {
                "asBinder" -> Binder()  // 返回一个空 binder，避免 NPE
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "ShizukuVirtualDisplayCallbackProxy"
                else -> null
            }
        }
    }

    /**
     * 构造 VirtualDisplayConfig（API 33+ Builder 模式）
     */
    private fun buildVirtualDisplayConfig(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface
    ): Any {
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass.getConstructor().newInstance()

        // 反射调用 builder 的 setters
        val setName = builderClass.getMethod("setName", String::class.java)
        val setWidth = builderClass.getMethod("setWidth", Int::class.javaPrimitiveType)
        val setHeight = builderClass.getMethod("setHeight", Int::class.javaPrimitiveType)
        val setDensity = builderClass.getMethod("setDensityDpi", Int::class.javaPrimitiveType)
        val setSurface = builderClass.getMethod("setSurface", Surface::class.java)

        setName.invoke(builder, name)
        setWidth.invoke(builder, width)
        setHeight.invoke(builder, height)
        setDensity.invoke(builder, density)
        setSurface.invoke(builder, surface)

        val build = builderClass.getMethod("build")
        return build.invoke(builder)
    }

    /**
     * 通过 Shizuku 创建虚拟显示器
     *
     * @param name    虚拟显示器名称
     * @param width   宽度（像素）
     * @param height  高度（像素）
     * @param density DPI
     * @param surface 画面输出目标 Surface
     * @return 成功返回 VirtualDisplayHandle；失败返回 null
     */
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface
    ): VirtualDisplayHandle? {
        val handle = getDisplayManagerHandle() ?: run {
            Log.e(TAG, "DisplayManagerHandle unavailable (Shizuku not granted?)")
            return null
        }

        return try {
            val config = buildVirtualDisplayConfig(name, width, height, density, surface)
            val callback = createCallbackProxy()

            // 按方法参数类型动态构造参数列表（兼容不同 Android 版本的签名变化）
            //   config       → VirtualDisplayConfig 实例
            //   callback     → IVirtualDisplayCallback 动态代理
            //   "com.autobot.app" → packageName
            //   Binder()     → IBinder / nativeToken 占位
            //   0 / 0L       → int / long 占位
            val paramTypes = handle.createVirtualDisplayMethod.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                args[i] = when {
                    type.name == "android.hardware.display.VirtualDisplayConfig" -> config
                    type.name == "android.hardware.display.IVirtualDisplayCallback" -> callback
                    type == String::class.java -> "com.autobot.app"
                    type == IBinder::class.java -> Binder()
                    type == Surface::class.java -> surface
                    type == Int::class.javaPrimitiveType -> 0
                    type == Long::class.javaPrimitiveType -> 0L
                    type == Boolean::class.javaPrimitiveType -> false
                    else -> {
                        Log.w(TAG, "Unknown parameter type at index $i: ${type.name}, using null")
                        null
                    }
                }
            }

            val virtualDisplay = handle.createVirtualDisplayMethod.invoke(handle.iDisplayManager, *args)

            if (virtualDisplay == null) {
                Log.e(TAG, "createVirtualDisplay returned null (permission denied or invalid args)")
                return null
            }

            // 拿到 VirtualDisplay 引用及其 release 方法（用于后续销毁）
            val virtualDisplayClass = Class.forName("android.hardware.display.VirtualDisplay")
            val releaseMethod = virtualDisplayClass.getMethod("release")

            // 反射获取 Display ID：VirtualDisplay.getDisplay().getDisplayId()
            //   用途：传给 am start --display <id> 让 App 启动到此虚拟显示器上
            val displayId = try {
                val getDisplay = virtualDisplayClass.getMethod("getDisplay")
                val display = getDisplay.invoke(virtualDisplay)
                val displayClass = Class.forName("android.view.Display")
                val getDisplayId = displayClass.getMethod("getDisplayId")
                getDisplayId.invoke(display) as Int
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get displayId via reflection, fallback to -1", e)
                -1
            }

            Log.i(TAG, "VirtualDisplay created: $name ${width}x${height} displayId=$displayId")
            VirtualDisplayHandle(virtualDisplay, releaseMethod, displayId)
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
    }
}
