package com.autobot.app.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.autobot.app.R
import rikka.shizuku.Shizuku

object ShizukuManager {

    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun isShizukuConnected(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    enum class ShizukuDiagnosis {
        OK,
        NOT_INSTALLED,
        NOT_CONNECTED,
        NOT_GRANTED,
        UNKNOWN_ERROR
    }

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

    fun requestShizukuPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        return try {
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

    fun getShizukuStatusText(context: Context): String {
        return when {
            !isShizukuInstalled(context) -> context.getString(R.string.shizuku_not_installed)
            !isShizukuConnected() -> "Shizuku未连接"
            !isShizukuGranted() -> context.getString(R.string.shizuku_unauthorized)
            else -> context.getString(R.string.shizuku_authorized)
        }
    }

    fun getShizukuStatusCode(context: Context): Int {
        return when {
            !isShizukuInstalled(context) -> 0
            !isShizukuConnected() -> 1
            !isShizukuGranted() -> 2
            else -> 3
        }
    }
}
