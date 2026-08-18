package com.autobot.app.third

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 伪装 shell 身份的 Context 包装器。
 *
 * 核心思路（照搬 MAA-Meow 的 FakeContext）：
 * - getPackageName() 返回 "com.android.shell"
 * - getAttributionSource() 用 Process.SHELL_UID
 * - checkCallingPermission() 恒返回 GRANTED
 *
 * 这样在通过 ShizukuBinderWrapper 转发的 binder 调用中，
 * 系统服务的 packageName/uid 校验不会拒绝。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
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

    companion object {
        private const val TAG = "FakeContext"
        const val PACKAGE_NAME = "com.android.shell"

        @Volatile
        private var instance: FakeContext? = null

        fun get(base: Context): FakeContext {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val ctx = base.applicationContext ?: base
                val fake = FakeContext(ctx)
                instance = fake
                Log.i(TAG, "FakeContext created with packageName=$PACKAGE_NAME, uid=${Process.SHELL_UID}")
                return fake
            }
        }
    }
}
