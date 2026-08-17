package com.autobot.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.autobot.app.manager.ScriptTaskManager
import com.autobot.app.manager.ShizukuManager
import rikka.shizuku.Shizuku

/**
 * AutoBOT 应用全局 Application 类
 * 用于初始化全局配置和监听 Shizuku 连接状态
 */
class AutoBOTApp : Application() {

    companion object {
        private const val TAG = "AutoBOTApp"

        private var instance: AutoBOTApp? = null

        fun getInstance(): AutoBOTApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        // 在 super.onCreate 之前启用 Vector 资源兼容 —— 修复某些系统版本下 Switch / CompoundButton
        // 加载 Drawable 资源时的间接 NPE（与 StaticLayout null 同源的兼容性坑）
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        // 强制浅色模式，保证白色主题始终生效
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate()
        instance = this

        // 1. 注册全局未捕获异常处理器，避免"一打开就闪退"后无任何提示
        //    生产环境可上报崩溃到后台，此处记录日志便于调试
        installGlobalCrashHandler()

        // 2. 初始化 SH 脚本任务管理器（加载已持久化的脚本任务列表）
        try {
            ScriptTaskManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "ScriptTaskManager init failed", e)
        }

        // 3. 监听 Shizuku 连接状态变化
        //    注意：Shizuku 未安装或 Shizuku 服务未启动时，addBinderReceivedListener 会抛异常
        //    必须先检查 pingBinder 且所有 Shizuku API 调用都套 try-catch
        try {
            if (ShizukuManager.isShizukuInstalled(this)) {
                Shizuku.addBinderReceivedListener {
                    Log.i(TAG, "Shizuku binder received: connected")
                }
                Shizuku.addBinderDeadListener {
                    Log.i(TAG, "Shizuku binder dead: disconnected")
                }
            } else {
                Log.i(TAG, "Shizuku not installed, skip Shizuku listener registration")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Register Shizuku listeners skipped (Shizuku not available)", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    /**
     * 全局未捕获异常处理器
     *
     * 避免"启动即闪退"场景下没有任何线索。
     * - 向 Logcat 输出完整堆栈（可通过 adb logcat 抓到）
     * - 保留默认处理器继续抛出，不掩盖异常
     */
    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                TAG,
                "\n========================= CRASH =========================\n" +
                        "Thread: ${thread.name}\n" +
                        Log.getStackTraceString(throwable) +
                        "\n=========================================================",
                throwable
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
