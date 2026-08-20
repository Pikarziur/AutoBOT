package com.autobot.app.third

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log
import java.lang.reflect.Field

// 注意：不直接 import android.content.IContentProvider —— 它是 @hide 接口，
// 在标准 Android SDK（compileSdk=34）中不可见，直接 import 会触发
// "Unresolved reference: IContentProvider" 编译错误。
// ShellContentResolver 内部使用 Any? 类型 + 反射方式处理 Provider 实例。

/**
 * 伪装 shell 身份的 Context 包装器（对齐 MAA-Meow/scrcpy 的 FakeContext.java）。
 *
 * 关键设计（含踩坑）：
 * 1. base 必须用 Workarounds.getSystemContext()，不能用 App 的 applicationContext
 *    ——DisplayManager 构造器需要通过它拿正确的系统服务和 ActivityThread。
 * 2. ShellContentResolver 重写隐藏的 acquireProvider()/releaseProvider()，通过
 *    ActivityManager.getContentProviderExternal() 走 shell UID 的 Provider 查询；
 *    否则 DisplayManager 内部需要 ContentProvider 时会报 SecurityException（App UID vs shell UID 不一致）。
 * 3. getSystemService() 对 CLIPBOARD_SERVICE 替换 mContext 为 FakeContext，
 *    避免 ClipboardManager 内部持有真实 App Context 引发权限问题。
 *
 * 使用：首次调用 get() 前必须先 Workarounds.apply()。
 */
@SuppressLint("PrivateApi, DiscouragedPrivateApi")
class FakeContext private constructor(base: Context) : ContextWrapper(base) {

    override fun getPackageName(): String = PACKAGE_NAME

    override fun getOpPackageName(): String = PACKAGE_NAME

    @TargetApi(Build.VERSION_CODES.S)
    override fun getAttributionSource(): AttributionSource {
        return AttributionSource.Builder(Process.SHELL_UID)
            .setPackageName(PACKAGE_NAME)
            .build()
    }

    override fun checkCallingPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED

    override fun getApplicationContext(): Context = this

    // 具名子类而非匿名类：避免 R8 在某些优化模式下把隐藏方法 override 当死代码删掉
    private val contentResolver: ContentResolver = ShellContentResolver(this)

    override fun getContentResolver(): ContentResolver = contentResolver

    private class ShellContentResolver(context: Context) : ContentResolver(context) {

        // 注意：acquireProvider 等方法的返回类型在父类 ContentResolver 中是隐藏的 IContentProvider，
        // 标准 SDK 不可见，因此用 Any? 兜底，运行时通过反射取 holder.provider 字段（类型为 IContentProvider）。
        @Suppress("unused", "ProtectedMemberInFinalClass")
        // @Override（super 方法隐藏，编译期不可见）
        protected fun acquireProvider(c: Context, name: String): Any? {
            // 通过 ActivityManager.getContentProviderExternal(name, new Binder()) —— AIDL 调用在 shell UID 下允许
            return try {
                val amClass = Class.forName("android.app.ActivityManagerNative")
                val getDefault = amClass.getDeclaredMethod("getDefault")
                val am = getDefault.invoke(null)
                val getContentProviderExternal = am.javaClass.getDeclaredMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Binder::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val holder = getContentProviderExternal.invoke(am, name, Binder(), 0, 0)
                    ?: return null
                val providerField = holder.javaClass.getDeclaredField("provider")
                providerField.isAccessible = true
                providerField.get(holder)
            } catch (e: Exception) {
                Log.w(TAG, "ShellContentResolver.acquireProvider($name) failed", e)
                null
            }
        }

        @Suppress("unused")
        fun releaseProvider(icp: Any?): Boolean = false

        @Suppress("unused", "ProtectedMemberInFinalClass")
        protected fun acquireUnstableProvider(c: Context, name: String): Any? = null

        @Suppress("unused")
        fun releaseUnstableProvider(icp: Any?): Boolean = false

        @Suppress("unused")
        fun unstableProviderDied(icp: Any?) { /* ignore */ }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null
        if (Context.CLIPBOARD_SERVICE == name) {
            try {
                val field: Field = ClipboardManager::class.java.getDeclaredField("mContext")
                field.isAccessible = true
                field.set(service, this)
            } catch (e: ReflectiveOperationException) {
                throw RuntimeException(e)
            }
        }
        return service
    }

    companion object {
        private const val TAG = "FakeContext"
        const val PACKAGE_NAME = "com.android.shell"
        const val ROOT_UID = 0

        @Volatile
        private var instance: FakeContext? = null

        /** 获取 FakeContext 单例；首次调用前必须 Workarounds.apply()。 */
        fun get(base: Context? = null): FakeContext {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }

                Workarounds.apply()

                // 优先用 Workarounds.getSystemContext()（系统级 Context）
                val sysCtx = Workarounds.getSystemContext()
                val ctx = sysCtx ?: base?.applicationContext ?: base
                ?: error("Workarounds.getSystemContext() returned null and no fallback base provided. Call Workarounds.apply() first.")

                val fake = FakeContext(ctx)
                instance = fake
                Log.i(TAG, "FakeContext created: packageName=$PACKAGE_NAME, uid=${Process.SHELL_UID}, base=${ctx.javaClass.simpleName}")
                return fake
            }
        }

        /** 显式重置（测试或进程热重启场景）。 */
        fun resetForTest() {
            instance = null
        }
    }
}
