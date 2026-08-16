package com.autobot.app.manager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import com.autobot.app.util.ShellExecutor

/**
 * 本地应用管理工具
 *
 * 职责：
 * 1. 获取手机上已安装、可被 Intent 启动的第三方/系统应用列表（含包名、应用名、图标）
 * 2. 通过 Shizuku 的 `am start` 命令启动指定包名的应用（可在后台、虚拟显示器中启动）
 *
 * 默认选择淘宝（包名：com.taobao.taobao），用户可自由切换为手机上的其他应用。
 *
 * 读取应用列表需要权限：
 *   - Android 11+：QUERY_ALL_PACKAGES（已在 Manifest 中声明）
 *   - Android 10 及以下：默认即可读取
 */
object AppManager {

    private const val TAG = "AppManager"

    /** 淘宝包名：默认选中的启动应用 */
    const val DEFAULT_PACKAGE_TAOBAO = "com.taobao.taobao"

    /**
     * 应用信息数据类
     */
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    ) {
        /** UI 展示文本：应用名(包名) */
        val displayText: String get() = "$appName($packageName)"
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
        // MATCH_ALL：返回所有匹配项，包括禁用的组件（避免漏掉被系统禁用但有 launcher 入口的 App）
        //   注：MATCH_DEFAULT_ONLY 只会返回带 DEFAULT category 的，会漏掉很多 App
        //   Android 11+ 还需 QUERY_ALL_PACKAGES 权限（已在 Manifest 声明）
        val flag = PackageManager.MATCH_ALL
        val resolveInfos = pm.queryIntentActivities(launchIntent, flag)

        val seenPackages = HashSet<String>()
        val result = ArrayList<AppInfo>(resolveInfos.size)

        for (ri in resolveInfos) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val packageName = ai.packageName
            // 一个应用可能多个 launcher 入口（多开、双开场景），去重保首次
            if (seenPackages.contains(packageName)) continue
            seenPackages.add(packageName)

            // 按参数过滤系统应用
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

        // 按应用名自然排序
        result.sortBy { it.appName }
        Log.i(TAG, "Found ${result.size} launchable apps (includeSystem=$includeSystem)")
        return result
    }

    /**
     * 通过包名查找 AppInfo（用于定位默认"淘宝"是否存在）
     */
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
     *   1. 优先通过 Shizuku 调用 `am start` 命令启动（可跨进程/后台启动，稳定性更高）
     *      - 若 displayId > 0：附加 `--display <displayId>` 让 App 启动到指定虚拟显示器
     *        （这样 App 画面会出现在虚拟显示器预览区，而不是跳转到前台主屏）
     *   2. 若 Shizuku 不可用则回退为普通 Context.startActivity（需要前台可见，且受后台启动限制）
     *      注意：回退路径无法指定 displayId，App 会在默认显示器（前台）启动
     *
     * @param context     回退到 startActivity 时使用；可传 null（当确认 Shizuku 可用时）
     * @param packageName 目标应用包名
     * @param displayId   虚拟显示器 ID（>0 时通过 am start --display 启动到虚拟显示器；
     *                    <=0 时在默认显示器/前台启动）
     * @return true 表示启动命令已成功发出
     */
    fun launchApp(context: Context?, packageName: String, displayId: Int = -1): Boolean {
        if (packageName.isBlank()) {
            Log.w(TAG, "launchApp: empty packageName")
            return false
        }

        // 1. Shizuku 路径：am start -n 启动 launcher Activity；若启动失败再用 monkey -p 兜底
        if (com.autobot.app.manager.ShizukuManager.isShizukuGranted()) {
            // 构造 am start 命令：可选附加 --display <id> 启动到虚拟显示器
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

            // 回退：用 monkey 模拟点击启动（不需要知道具体 Activity 名）
            //   monkey 不支持 --display，无法启动到虚拟显示器，仅作为兜底
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

        // 2. 普通路径：从当前前台 Activity 启动（无法指定 displayId）
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

    /**
     * 解析应用的 launcher Activity 类名（用于 `am start -n pkg/cls` 命令）
     */
    private fun resolveLauncherActivity(context: Context?, packageName: String): String? {
        val ctx = context ?: return null
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
        val cn = intent.component ?: return null
        return cn.className
    }
}
