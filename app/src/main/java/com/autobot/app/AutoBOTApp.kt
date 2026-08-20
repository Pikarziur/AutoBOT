package com.autobot.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.TaskFileManager
import com.autobot.app.service.CompositionService
import rikka.shizuku.Shizuku

/**
 * AutoBOT 应用全局 Application 类
 * 用于初始化全局配置和监听 Shizuku 连接状态
 *
 * ★重要变更（替代 SH 脚本模式）★：
 *   - 不再 init ScriptTaskManager（已删除，旧版 adb shell 路径全部清理）
 *   - 改为 init TaskFileManager：扫描 filesDir/tasks/ 加载 JSON 任务文件
 *   - 持有 Application 级 [compositionService] 单例，让 VD 与 server pipe 跨 Activity 生命周期保活
 *     （配合 TaskService 前台服务，应用切到后台/小窗仍能持续执行任务动作）
 */
class AutoBOTApp : Application() {

    companion object {
        private const val TAG = "AutoBOTApp"

        private var instance: AutoBOTApp? = null

        fun getInstance(): AutoBOTApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }

        /**
         * app 级 CompositionService 单例（跨 Activity 生命周期）
         *
         * MonitorViewModel/TaskExecutor 都从这里取，避免 Activity 销毁时 VD 被一起 stop。
         * 真正的 VD 释放时机：用户主动停止 VD，或 App 进程被系统杀掉时随进程一起清理
         * （server 进程的 stdin pipe EOF 后会自动 exit）。
         */
        @Volatile
        private var compositionServiceInstance: CompositionService? = null

        fun getCompositionService(): CompositionService {
            return compositionServiceInstance ?: synchronized(this) {
                compositionServiceInstance ?: CompositionService(getInstance()).also {
                    compositionServiceInstance = it
                }
            }
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

        // 2. 初始化任务文件管理器（替代旧版 ScriptTaskManager）
        //    扫描 filesDir/tasks/ 加载 JSON 任务文件 + 从 assets/tasks/ 装载预置任务
        try {
            TaskFileManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "TaskFileManager init failed", e)
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
