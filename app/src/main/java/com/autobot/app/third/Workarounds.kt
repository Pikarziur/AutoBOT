package com.autobot.app.third

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 系统兼容性工作区（照搬 MAA-Meow/scrcpy 的 Workarounds.java）。
 *
 * 踩坑：虚拟显示器创建路径内部会走 ActivityThread.currentActivityThread().getConfiguration()、
 * ContentProvider.acquireProvider() 等系统调用；当前进程没有准备好 ActivityThread 实例
 * （非 App 主线程，或需用 "com.android.shell" 身份的 mBoundApplication）时这些调用会 NPE。
 *
 * 关键约定：
 *   - Android 12+ 必须先 fillConfigurationController()，否则三星等设备 getDisplayInfoLocked() NPE
 *   - 必须在 FakeContext.get() / DisplayManagerHelper.createVirtualDisplay() 之前调用 apply()
 */
@SuppressLint("PrivateApi, BlockedPrivateApi, SoonBlockedPrivateApi, DiscouragedPrivateApi")
object Workarounds {

    private const val TAG = "Workarounds"

    private val activityThreadClass: Class<*>
    private val activityThread: Any

    /**
     * 重入守卫：防止 apply() → fillAppContext() → FakeContext.get() → apply() 无限递归 StackOverflow。
     * 第二次进入时 applying=true 直接返回（init 块已建好 ActivityThread，getSystemContext() 可用）。
     */
    @Volatile
    private var applying = false

    init {
        try {
            prepareMainLooper()

            activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThreadConstructor: Constructor<*> = activityThreadClass.getDeclaredConstructor()
            activityThreadConstructor.isAccessible = true
            activityThread = activityThreadConstructor.newInstance()

            val sCurrentActivityThreadField: Field = activityThreadClass.getDeclaredField("sCurrentActivityThread")
            sCurrentActivityThreadField.isAccessible = true
            sCurrentActivityThreadField.set(null, activityThread)

            val mSystemThreadField: Field = activityThreadClass.getDeclaredField("mSystemThread")
            mSystemThreadField.isAccessible = true
            mSystemThreadField.setBoolean(activityThread, true)

            Log.i(TAG, "ActivityThread prepared: sCurrentActivityThread injected, mSystemThread=true")
        } catch (e: Exception) {
            Log.e(TAG, "ActivityThread init failed", e)
            throw AssertionError(e)
        }
    }

    /** 入口：一次性调用，准备好全部系统环境。多次调用安全（幂等 + 重入守卫）。 */
    @JvmStatic
    fun apply() {
        if (applying) {
            Log.d(TAG, "apply() reentrant call, skipped (ActivityThread already initialized)")
            return
        }
        applying = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 必须先填充 ConfigurationController，再 fillAppContext
                fillConfigurationController()
            }

            // ONYX 设备特殊处理：fillAppInfo() 会破坏镜像，这里不做品牌跳过（AutoBOT 虚拟显示器场景不受影响）
            fillAppInfo()
            fillAppContext()

            Log.i(TAG, "apply() finished")
        } finally {
            applying = false
        }
    }

    private fun prepareMainLooper() {
        // 类似 Looper.prepareMainLooper()，但 quitAllowed=true
        Looper.prepare()
        synchronized(Looper::class.java) {
            try {
                @SuppressLint("DiscouragedPrivateApi")
                val field: Field = Looper::class.java.getDeclaredField("sMainLooper")
                field.isAccessible = true
                field.set(null, Looper.myLooper())
                Log.i(TAG, "sMainLooper prepared (quitAllowed=true)")
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }
    }

    private fun fillAppInfo() {
        try {
            val appBindDataClass: Class<*> = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor: Constructor<*> = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData: Any = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = FakeContext.PACKAGE_NAME

            val appInfoField: Field = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, applicationInfo)

            val mBoundApplicationField: Field = activityThreadClass.getDeclaredField("mBoundApplication")
            mBoundApplicationField.isAccessible = true
            mBoundApplicationField.set(activityThread, appBindData)

            Log.i(TAG, "fillAppInfo: packageName=${FakeContext.PACKAGE_NAME}")
        } catch (t: Throwable) {
            // 仅调试日志，不抛出——这是 workaround，失败可接受
            Log.d(TAG, "fillAppInfo failed: ${t.message}")
        }
    }

    private fun fillAppContext() {
        try {
            val app: Application = Instrumentation.newApplication(Application::class.java, FakeContext.get())
            val mInitialApplicationField: Field = activityThreadClass.getDeclaredField("mInitialApplication")
            mInitialApplicationField.isAccessible = true
            mInitialApplicationField.set(activityThread, app)
            Log.i(TAG, "fillAppContext: mInitialApplication injected")
        } catch (t: Throwable) {
            Log.d(TAG, "fillAppContext failed: ${t.message}")
        }
    }

    @SuppressLint("NewApi")
    private fun fillConfigurationController() {
        try {
            val configurationControllerClass: Class<*> = Class.forName("android.app.ConfigurationController")
            val activityThreadInternalClass: Class<*> = Class.forName("android.app.ActivityThreadInternal")

            val ctor: Constructor<*> = configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass)
            ctor.isAccessible = true
            val configurationController: Any = ctor.newInstance(activityThread)

            val field: Field = activityThreadClass.getDeclaredField("mConfigurationController")
            field.isAccessible = true
            field.set(activityThread, configurationController)

            Log.i(TAG, "fillConfigurationController: Android 12+ ConfigurationController injected")
        } catch (t: Throwable) {
            Log.d(TAG, "fillConfigurationController failed: ${t.message}")
        }
    }

    /** 获取系统级 Context；FakeContext 用它作为 base，而非 App 的 applicationContext。 */
    @JvmStatic
    fun getSystemContext(): Context? {
        return try {
            val getSystemContextMethod: Method = activityThreadClass.getDeclaredMethod("getSystemContext")
            val ctx = getSystemContextMethod.invoke(activityThread) as? Context
            if (ctx != null) {
                Log.i(TAG, "getSystemContext: success, packageName=${ctx.packageName}")
            }
            ctx
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            Log.d(TAG, "getSystemContext failed: $sw")
            null
        }
    }
}
