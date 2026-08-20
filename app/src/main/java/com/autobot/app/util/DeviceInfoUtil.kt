package com.autobot.app.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 设备信息工具类
 * 获取屏幕分辨率、设备信息等
 */
object DeviceInfoUtil {

    /**
     * 获取屏幕分辨率（像素）
     * @return Pair<width: Int, height: Int>
     */
    fun getScreenResolution(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()

        // 获取默认显示的真实尺寸（包含状态栏、导航栏）
        return try {
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        } catch (e: Exception) {
            // 降级方案：使用常规方法
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    /**
     * 获取屏幕分辨率描述字符串
     * 格式：宽x高 (dpi)
     */
    fun getScreenResolutionText(context: Context): String {
        val (width, height) = getScreenResolution(context)
        val densityDpi = context.resources.displayMetrics.densityDpi
        return "${width}x${height} ($densityDpi dpi)"
    }

    /**
     * 获取应用版本名
     */
    fun getAppVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.03"
        } catch (e: Exception) {
            "0.03"
        }
    }

    /**
     * 获取应用版本号
     */
    fun getAppVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1
        }
    }

    /**
     * 获取设备型号
     */
    fun getDeviceModel(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }

    /**
     * 获取 Android 版本
     */
    fun getAndroidVersion(): String {
        return "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    }
}
