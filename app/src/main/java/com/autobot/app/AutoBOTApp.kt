package com.autobot.app

import android.app.Application
import com.autobot.app.manager.ScriptTaskManager
import rikka.shizuku.Shizuku

/**
 * AutoBOT 应用全局 Application 类
 * 用于初始化全局配置和监听 Shizuku 连接状态
 */
class AutoBOTApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化 SH 脚本任务管理器（加载已持久化的脚本任务列表）
        ScriptTaskManager.init(this)

        // 监听 Shizuku 连接状态变化
        Shizuku.addBinderReceivedListener {
            // Shizuku 连接成功
        }
        Shizuku.addBinderDeadListener {
            // Shizuku 连接断开
        }
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    companion object {
        private var instance: AutoBOTApp? = null

        fun getInstance(): AutoBOTApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
