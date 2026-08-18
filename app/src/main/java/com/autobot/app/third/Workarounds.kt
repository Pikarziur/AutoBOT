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
 * 系统兼容性工作区（完全照搬 MAA-Meow/scrcpy 的 Workarounds.java）。
 *
 * 为什么需要这个？
 * 虚拟显示器创建路径（DisplayManager(Context) 构造器 + createVirtualDisplay）
 * 内部会走 ActivityThread.currentActivityThread().getConfiguration()、
 * ContentProvider.acquireProvider() 等系统调用。如果当前进程没有准备好
 * ActivityThread 实例（非 App 主线程场景，或即使是 App 主线程也需要用
 * "com.android.shell" 身份的 mBoundApplication），这些调用会 NPE 或返回错误。
 *
 * 这个类负责：
 *  1. prepareMainLooper() —— 手动设置 sMainLooper（可退出）
 *  2. 实例化 ActivityThread 并注入 sCurrentActivityThread
 *  3. 设 mSystemThread=true（避免 App 身份的校验）
 *  4. fillAppInfo() —— 填充 mBoundApplication.appInfo.packageName="com.android.shell"
 *  5. fillAppContext() —— 填充 mInitialApplication = Application(FakeContext)
 *  6. fillConfigurationController() —— Android 12+ 必须，否则三星等设备 getDisplayInfoLocked() NPE
 *  7. getSystemContext() —— 通过 ActivityThread.getSystemContext() 拿到真正的系统级 Context
 *
 * 必须在 FakeContext.get() / DisplayManagerHelper.createVirtualDisplay() 之前调用 apply()。
 */
@SuppressLint("PrivateApi, BlockedPrivateApi, SoonBlockedPrivateApi, DiscouragedPrivateApi")
object Workarounds {

    private const val TAG = "Workarounds"

    private val activityThreadClass: Class<*>
    private val activityThread: Any

    init {
        try {
            prepareMainLooper()

            // ActivityThread activityThread = new ActivityThread()
            activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThreadConstructor: Constructor<*> = activityThreadClass.getDeclaredConstructor()
            activityThreadConstructor.isAccessible = true
            activityThread = activityThreadConstructor.newInstance()

            // ActivityThread.sCurrentActivityThread = activityThread
            val sCurrentActivityThreadField: Field = activityThreadClass.getDeclaredField("sCurrentActivityThread")
            sCurrentActivityThreadField.isAccessible = true
            sCurrentActivityThreadField.set(null, activityThread)

            // activityThread.mSystemThread = true
            val mSystemThreadField: Field = activityThreadClass.getDeclaredField("mSystemThread")
            mSystemThreadField.isAccessible = true
            mSystemThreadField.setBoolean(activityThread, true)

            Log.i(TAG, "ActivityThread prepared: sCurrentActivityThread injected, mSystemThread=true")
        } catch (e: Exception) {
            Log.e(TAG, "ActivityThread init failed", e)
            throw AssertionError(e)
        }
    }

    /**
     * 入口：一次性调用，准备好全部系统环境。
     * 多次调用安全（幂等）。
     */
    @JvmStatic
    fun apply() {
        // 已经在静态 init 块中准备了 ActivityThread
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 必须先填充 ConfigurationController，再 fillAppContext
            // （fillAppContext 依赖 getSystemContext，后者在三星等设备上会调 getConfiguration()）
            fillConfigurationController()
        }

        // ONYX 设备特殊处理：fillAppInfo() 会破坏镜像，这里不做品牌跳过（AutoBOT 虚拟显示器场景不受影响）
        fillAppInfo()
        fillAppContext()

        Log.i(TAG, "apply() finished")
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
            // ActivityThread.AppBindData appBindData = new ActivityThread.AppBindData()
            val appBindDataClass: Class<*> = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor: Constructor<*> = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData: Any = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = FakeContext.PACKAGE_NAME

            // appBindData.appInfo = applicationInfo
            val appInfoField: Field = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, applicationInfo)

            // activityThread.mBoundApplication = appBindData
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
            // activityThread.mInitialApplication = app
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

            // new ConfigurationController(activityThreadInternalClass instance = our ActivityThread)
            val ctor: Constructor<*> = configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass)
            ctor.isAccessible = true
            val configurationController: Any = ctor.newInstance(activityThread)

            // activityThread.mConfigurationController = configurationController
            val field: Field = activityThreadClass.getDeclaredField("mConfigurationController")
            field.isAccessible = true
            field.set(activityThread, configurationController)

            Log.i(TAG, "fillConfigurationController: Android 12+ ConfigurationController injected")
        } catch (t: Throwable) {
            Log.d(TAG, "fillConfigurationController failed: ${t.message}")
        }
    }

    /**
     * 获取系统级 Context（通过 ActivityThread.getSystemContext()）。
     * FakeContext 应该用这个作为 base，而不是 App 的 applicationContext。
     */
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
