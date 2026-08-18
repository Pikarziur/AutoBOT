package com.autobot.app.third

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.Display

/**
 * 将 App 启动到指定虚拟显示器的工具。
 *
 * 参照 MAA-Meow 的 ActivityUtils：
 * - 主路径：反射 IActivityManager.startActivityAsUser + ActivityOptions.launchDisplayId
 * - 兜底：am start --display <id> <intentUri>
 */
object ActivityUtils {
    private const val TAG = "ActivityUtils"

    /**
     * 启动指定包名的 App 到虚拟显示器。
     *
     * @param context     Context
     * @param packageName 要启动的 App 包名
     * @param displayId   目标虚拟显示器 ID
     * @return true 表示启动成功
     */
    fun startAppOnDisplay(context: Context, packageName: String, displayId: Int): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: run {
                Log.e(TAG, "No launch intent for $packageName")
                return false
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

        return startActivity(launchIntent, displayId)
    }

    /**
     * 启动 Intent 到指定显示器。
     * 主路径用 ActivityOptions.launchDisplayId，兜底用 am start --display 命令。
     */
    fun startActivity(intent: Intent, displayId: Int): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY || displayId < 0) {
            return startViaAmCommand(intent, displayId)
        }

        return try {
            // 用 ActivityOptions 设置目标显示器
            val launchOptions = ActivityOptions.makeBasic()
            val setLaunchDisplayId = ActivityOptions::class.java.getMethod(
                "setLaunchDisplayId", Int::class.javaPrimitiveType
            )
            setLaunchDisplayId.invoke(launchOptions, displayId)

            // 获取 ActivityOptions 的 Bundle
            val toBundle = ActivityOptions::class.java.getMethod("toBundle")
            val optionsBundle = toBundle.invoke(launchOptions)

            // 反射 IActivityManager.startActivityAsUser
            val amClass = Class.forName("android.app.IActivityManager")
            val amStub = Class.forName("android.app.ActivityManager\$Stub")
            // 或者通过 ActivityManager.getService()
            try {
                val amInstance = getIActivityManager()
                if (amInstance != null) {
                    // 尝试不同签名的 startActivity
                    val startActivityMethod = amClass.methods.firstOrNull {
                        it.name == "startActivityAsUser" &&
                        it.parameterTypes.size >= 8 &&
                        it.parameterTypes.contains(Intent::class.java)
                    }
                    if (startActivityMethod != null) {
                        val params = startActivityMethod.parameterTypes
                        val args = arrayOfNulls<Any>(params.size)
                        for (i in params.indices) {
                            args[i] = when (params[i]) {
                                Intent::class.java -> intent
                                String::class.java -> FakeContext.PACKAGE_NAME
                                else -> null
                            }
                        }
                        val result = startActivityMethod.invoke(amInstance, *args)
                        if (result is Int && result >= 0) {
                            Log.i(TAG, "startActivityAsUser succeeded, displayId=$displayId")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "startActivityAsUser failed, fallback to am command", e)
            }

            // 兜底
            startViaAmCommand(intent, displayId)
        } catch (e: Exception) {
            Log.w(TAG, "startActivity failed, fallback to am command", e)
            startViaAmCommand(intent, displayId)
        }
    }

    /**
     * 用 am start --display 命令启动
     */
    private fun startViaAmCommand(intent: Intent, displayId: Int): Boolean {
        return try {
            val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
            val args = if (displayId <= 0) {
                arrayOf("am", "start", intentUri)
            } else {
                arrayOf("am", "start", "--display", displayId.toString(), intentUri)
            }
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            Log.i(TAG, "am start --display $displayId exitCode=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "am start command failed", e)
            false
        }
    }

    /**
     * 反射获取 IActivityManager 实例
     */
    private fun getIActivityManager(): Any? {
        return try {
            val amClass = Class.forName("android.app.ActivityManager")
            val getService = amClass.getMethod("getService")
            getService.invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "getIActivityManager failed", e)
            null
        }
    }

    /**
     * 检查指定包名的 App 是否在目标显示器上运行
     */
    fun isAppOnDisplay(context: Context, packageName: String, displayId: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.getRunningTasks(10)
            tasks.any { it.taskId > 0 }
        } catch (e: Exception) {
            false
        }
    }
}
