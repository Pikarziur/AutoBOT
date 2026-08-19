package com.autobot.app.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.autobot.app.R
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限管理器
 * 负责检查、请求 Shizuku 权限，并提供跳转 Shizuku 应用进行授权的功能
 */
object ShizukuManager {

    // Shizuku 包名
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"

    /**
     * 检查 Shizuku 是否已安装
     */
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            // 尝试另一个包名（新版 Shizuku）
            try {
                context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * 检查 Shizuku 是否已连接（Binder 可用）
     */
    fun isShizukuConnected(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shizuku 详细诊断结果（UI 可以展示更精确的提示）
     */
    enum class ShizukuDiagnosis {
        OK,                 // 一切正常：已安装 + 已连接 + 已授权
        NOT_INSTALLED,      // Shizuku App 未安装
        NOT_CONNECTED,      // Shizuku 服务未启动 / Binder 不可达（用户没在 Shizuku App 里启动服务）
        NOT_GRANTED,        // Shizuku 服务已连接但本 App 未被授权（用户拒绝了权限弹窗）
        UNKNOWN_ERROR       // 调用过程中出现异常（详见日志）
    }

    /**
     * 详细诊断 Shizuku 状态（不只是 Boolean，调用方可根据不同情况给出精准文案）
     */
    fun diagnoseShizuku(context: Context): ShizukuDiagnosis {
        return try {
            if (!isShizukuInstalled(context)) {
                ShizukuDiagnosis.NOT_INSTALLED
            } else if (!isShizukuConnected()) {
                ShizukuDiagnosis.NOT_CONNECTED
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ShizukuDiagnosis.NOT_GRANTED
            } else {
                ShizukuDiagnosis.OK
            }
        } catch (e: Exception) {
            android.util.Log.w("ShizukuManager", "diagnoseShizuku exception: ${e.message}")
            ShizukuDiagnosis.UNKNOWN_ERROR
        }
    }

    /**
     * 获取诊断文本（用于 Toast / 日志）
     */
    fun getDiagnosisText(context: Context, diag: ShizukuDiagnosis): String {
        return when (diag) {
            ShizukuDiagnosis.OK -> "Shizuku 已授权"
            ShizukuDiagnosis.NOT_INSTALLED -> context.getString(R.string.shizuku_not_installed)
            ShizukuDiagnosis.NOT_CONNECTED -> "Shizuku 服务未启动：请打开 Shizuku App 并启动服务（通过 ADB / Root）"
            ShizukuDiagnosis.NOT_GRANTED -> "Shizuku 已连接但未授权：请打开 Shizuku App 并在「已授权的应用」中授权本应用"
            ShizukuDiagnosis.UNKNOWN_ERROR -> "Shizuku 状态异常：请重启 Shizuku 服务后重试"
        }
    }

    /**
     * 检查是否已获得 Shizuku 权限
     * 注意：Shizuku v13+ 已移除 checkCallingPermission()，统一使用 checkSelfPermission()
     */
    fun isShizukuGranted(): Boolean {
        return try {
            if (!isShizukuConnected()) {
                return false
            }
            // Shizuku v11+ 统一使用 checkSelfPermission
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求 Shizuku 权限
     * @param requestCode 请求码，用于回调识别
     */
    fun requestShizukuPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 跳转 Shizuku 应用，方便用户进行授权
     * @return 是否成功跳转
     */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            // 尝试新版 Shizuku
            val intent = Intent()
            val componentName = ComponentName(
                SHIZUKU_MANAGER_PACKAGE,
                "$SHIZUKU_MANAGER_PACKAGE.MainActivity"
            )
            intent.component = componentName
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 尝试旧版
            try {
                val intent = Intent()
                val componentName = ComponentName(
                    SHIZUKU_PACKAGE_NAME,
                    "moe.shizuku.manager.MainActivity"
                )
                intent.component = componentName
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                // 最后尝试从应用商店打开或列出启动入口
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
                    ?: context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * 获取 Shizuku 状态描述文字
     */
    fun getShizukuStatusText(context: Context): String {
        return when {
            !isShizukuInstalled(context) -> context.getString(R.string.shizuku_not_installed)
            !isShizukuConnected() -> "Shizuku未连接"
            !isShizukuGranted() -> context.getString(R.string.shizuku_unauthorized)
            else -> context.getString(R.string.shizuku_authorized)
        }
    }

    /**
     * 获取 Shizuku 状态码
     * 0: 未安装
     * 1: 未连接
     * 2: 已连接未授权
     * 3: 已授权
     */
    fun getShizukuStatusCode(context: Context): Int {
        return when {
            !isShizukuInstalled(context) -> 0
            !isShizukuConnected() -> 1
            !isShizukuGranted() -> 2
            else -> 3
        }
    }
}
