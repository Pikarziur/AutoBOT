package com.autobot.app.manager

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import com.autobot.app.util.ShellExecutor

/**
 * 本地应用管理工具
 *
 * 读取应用列表需要权限：
 *   - Android 11+：QUERY_ALL_PACKAGES（已在 Manifest 中声明）
 *   - Android 10 及以下：默认即可读取
 */
object AppManager {

    private const val TAG = "AppManager"

    const val DEFAULT_PACKAGE_TAOBAO = "com.taobao.taobao"

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    ) {
        val displayText: String get() = "$appName($packageName)"
    }

    enum class AppOrientation {
        PORTRAIT,
        LANDSCAPE,
        UNSPECIFIED
    }

    /**
     * 检测目标应用的首选屏幕方向（基于 Manifest 中声明的 launcher Activity 的 screenOrientation）
     *
     * 判定规则：
     *   - LANDSCAPE / REVERSE_LANDSCAPE / SENSOR_LANDSCAPE / USER_LANDSCAPE → LANDSCAPE
     *   - PORTRAIT / REVERSE_PORTRAIT / SENSOR_PORTRAIT / USER_PORTRAIT → PORTRAIT
     *   - UNSPECIFIED / USER / BEHIND / SENSOR / FULL_SENSOR / LOCKED → UNSPECIFIED
     */
    fun getAppPreferredOrientation(context: Context?, packageName: String): AppOrientation {
        if (packageName.isBlank()) return AppOrientation.UNSPECIFIED
        val ctx = context ?: return AppOrientation.UNSPECIFIED

        return try {
            val pm = ctx.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return AppOrientation.UNSPECIFIED
            val component = launchIntent.component
                ?: return AppOrientation.UNSPECIFIED

            val activityInfo = try {
                pm.getActivityInfo(component, PackageManager.GET_META_DATA)
            } catch (_: Exception) {
                // 部分 ROM（如 MIUI/HarmonyOS）对第三方隐藏 ActivityInfo，退回到查询 Manifest 中第一个 Activity
                try {
                    val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                    pkgInfo.activities?.firstOrNull()
                } catch (_: Exception) {
                    null
                }
            } ?: return AppOrientation.UNSPECIFIED

            when (activityInfo.screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> AppOrientation.LANDSCAPE

                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> AppOrientation.PORTRAIT

                else -> AppOrientation.UNSPECIFIED
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAppPreferredOrientation failed for $packageName", e)
            AppOrientation.UNSPECIFIED
        }
    }

    /**
     * 获取手机上所有已安装、可被启动（存在 CATEGORY_LAUNCHER 的入口 Activity）的应用
     *
     * @param context 用于访问 PackageManager
     * @param includeSystem 是否包含系统预装应用（true=全部，false=只含第三方）
     * @return 按应用名排序的 AppInfo 列表
     */
    fun getLaunchableApps(
        context: Context,
        includeSystem: Boolean = true
    ): List<AppInfo> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        // MATCH_ALL：返回所有匹配项；MATCH_DEFAULT_ONLY 只会返回带 DEFAULT category 的，会漏掉很多 App
        // Android 11+ 还需 QUERY_ALL_PACKAGES 权限（已在 Manifest 声明）
        val flag = PackageManager.MATCH_ALL
        val resolveInfos = pm.queryIntentActivities(launchIntent, flag)

        val seenPackages = HashSet<String>()
        val result = ArrayList<AppInfo>(resolveInfos.size)

        for (ri in resolveInfos) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val packageName = ai.packageName
            if (seenPackages.contains(packageName)) continue
            seenPackages.add(packageName)

            if (!includeSystem) {
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem) continue
            }

            val appName = try {
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                packageName
            }
            val icon = try {
                pm.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }
            result.add(AppInfo(packageName, appName, icon))
        }

        result.sortBy { it.appName }
        Log.i(TAG, "Found ${result.size} launchable apps (includeSystem=$includeSystem)")
        return result
    }

    fun getAppByPackageName(context: Context, packageName: String): AppInfo? {
        val pm = context.packageManager
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            val appName = try {
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                packageName
            }
            val icon = try {
                pm.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }
            AppInfo(packageName, appName, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 启动指定包名的应用
     *
     * 策略：
     *   1. 优先通过 Shizuku 调用 `am start`（可跨进程/后台启动，稳定性更高）
     *      - 若 displayId > 0：附加 `--display <displayId>` 让 App 启动到指定虚拟显示器
     *   2. 若 Shizuku 不可用则回退为普通 Context.startActivity（受后台启动限制，且无法指定 displayId）
     *
     * @param context     回退到 startActivity 时使用；可传 null（当确认 Shizuku 可用时）
     * @param packageName 目标应用包名
     * @param displayId   虚拟显示器 ID（>0 时通过 am start --display 启动到虚拟显示器；<=0 时在默认显示器/前台启动）
     * @return true 表示启动命令已成功发出
     */
    fun launchApp(context: Context?, packageName: String, displayId: Int = -1): Boolean {
        if (packageName.isBlank()) {
            Log.w(TAG, "launchApp: empty packageName")
            return false
        }

        if (com.autobot.app.manager.ShizukuManager.isShizukuGranted()) {
            val displayArg = if (displayId > 0) " --display $displayId" else ""
            val launchActivityCmd = "am start$displayArg -n $packageName/" +
                    (resolveLauncherActivity(context, packageName) ?: "")
            val r1 = ShellExecutor.execute(
                launchActivityCmd, useShizuku = true, timeout = 3000
            )
            if (r1.isSuccess) {
                Log.i(TAG, "launchApp (am start) success: $packageName displayId=$displayId")
                return true
            }

            // 回退：用 monkey -p 启动（不需要知道具体 Activity 名）；monkey 不支持 --display
            val monkeyCmd = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            val r2 = ShellExecutor.execute(
                monkeyCmd, useShizuku = true, timeout = 5000
            )
            if (r2.isSuccess) {
                Log.i(TAG, "launchApp (monkey) success: $packageName (no display target)")
                return true
            }
            Log.w(TAG, "launchApp shizuku failed: am=${r1.stderr}, monkey=${r2.stderr}")
        }

        val ctx = context ?: run {
            Log.e(TAG, "launchApp: context is null and shizuku unavailable")
            return false
        }
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(launchIntent)
                Log.i(TAG, "launchApp (startActivity) success: $packageName (default display)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "launchApp startActivity failed", e)
                false
            }
        } else {
            Log.w(TAG, "launchApp: no launcher activity for package=$packageName")
            false
        }
    }

    private fun resolveLauncherActivity(context: Context?, packageName: String): String? {
        val ctx = context ?: return null
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
        val cn = intent.component ?: return null
        return cn.className
    }

    /**
     * 强制停止指定包名的应用
     *
     * 通过 Shizuku 调用 `am force-stop` 关闭目标应用进程。
     * 用于"停止映射"场景：用户主动关闭映射到 VD 的目标应用。
     * 仅 Shizuku 可用时才能执行（普通应用无 force-stop 权限）。
     *
     * 命令组合说明：
     *   1. `am force-stop --user 0 <pkg>`：停止该 user 下应用的所有组件（Activity/Service/Receiver），
     *      ★必须显式指定 --user 0，部分 ROM 在不指定时 force-stop 可能不生效或作用于错误 user。
     *   2. `am kill --user 0 <pkg>`：清理可能残留的后台进程（force-stop 后理论上无前台进程，am kill 兜底）。
     *   3. `kill -9 $(pidof <pkg>)`：最终兜底，直接杀掉所有该包名进程，防止大厂应用保活机制重新拉起。
     *
     * @param packageName 目标应用包名
     * @return true 表示停止命令已成功执行（exit code == 0）
     */
    fun forceStopApp(packageName: String): Boolean {
        if (packageName.isBlank()) {
            Log.w(TAG, "forceStopApp: empty packageName")
            return false
        }
        if (!ShizukuManager.isShizukuGranted()) {
            Log.w(TAG, "forceStopApp: Shizuku not granted, cannot force-stop")
            return false
        }
        // 三重停止策略：force-stop 所有组件 → kill 后台进程 → 直接杀残留 PID
        val cmd = "am force-stop --user 0 $packageName; " +
                "am kill --user 0 $packageName; " +
                "for pid in \$(pidof $packageName); do kill -9 \$pid; done"
        val r = ShellExecutor.execute(cmd, useShizuku = true, timeout = 5000)
        if (r.isSuccess) {
            Log.i(TAG, "forceStopApp success: $packageName (force-stop + am kill + kill pid)")
            return true
        }
        // exit code 非零不代表完全失败（pidof 无匹配时 for 循环返回非零），
        // force-stop 可能已执行成功，仅记录警告
        Log.w(TAG, "forceStopApp exit=${r.exitCode}, stderr=${r.stderr}")
        // 只要 force-stop 部分执行了就认为基本成功（无法精确判断每条子命令结果）
        return r.exitCode == 0 || r.stderr.isBlank()
    }
}
