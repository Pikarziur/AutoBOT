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
     */
    class VirtualDisplayHandle(
        private val virtualDisplay: Any,
        private val releaseMethod: java.lang.reflect.Method
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

            // 4. 反射拿 createVirtualDisplay 方法
            //    API 33+: createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, String, IBinder)
            //    （不同版本参数有差异，这里以 API 33+ 的 4 参数签名为准，向下兼容在 catch 中处理）
            val iDisplayManagerClass = Class.forName("android.hardware.display.IDisplayManager")
            val virtualDisplayConfigClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")

            val method = try {
                // API 33+ 4 参数签名（包含 projectedDisplayUID）
                iDisplayManagerClass.getMethod(
                    "createVirtualDisplay",
                    virtualDisplayConfigClass,
                    callbackClass,
                    String::class.java,
                    IBinder::class.java
                )
            } catch (e: NoSuchMethodException) {
                // 退回到 3 参数签名（早期 API 33）
                iDisplayManagerClass.getMethod(
                    "createVirtualDisplay",
                    virtualDisplayConfigClass,
                    callbackClass,
                    String::class.java
                )
            }

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

            // 优先 4 参数签名（API 33+ 带 projectedDisplayUID）
            val virtualDisplay = try {
                handle.createVirtualDisplayMethod.invoke(
                    handle.iDisplayManager,
                    config,
                    callback,
                    "com.autobot.app",  // packageName
                    Binder()            // projectedDisplayUID / windowContextToken 占位
                )
            } catch (e: IllegalArgumentException) {
                // 退回到 3 参数签名
                handle.createVirtualDisplayMethod.invoke(
                    handle.iDisplayManager,
                    config,
                    callback,
                    "com.autobot.app"
                )
            }

            if (virtualDisplay == null) {
                Log.e(TAG, "createVirtualDisplay returned null (permission denied or invalid args)")
                return null
            }

            // 拿到 VirtualDisplay 引用及其 release 方法（用于后续销毁）
            val virtualDisplayClass = Class.forName("android.hardware.display.VirtualDisplay")
            val releaseMethod = virtualDisplayClass.getMethod("release")

            Log.i(TAG, "VirtualDisplay created: $name ${width}x${height}")
            VirtualDisplayHandle(virtualDisplay, releaseMethod)
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
    }
}
