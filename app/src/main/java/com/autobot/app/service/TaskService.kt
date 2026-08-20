package com.autobot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.autobot.app.R
import com.autobot.app.manager.TaskExecutor
import com.autobot.app.manager.TaskManager
import com.autobot.app.ui.MainActivity

class TaskService : Service() {

    companion object {
        private const val CHANNEL_ID = "autobot_task_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.autobot.action.START_SERVICE"
        private const val ACTION_STOP = "com.autobot.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, TaskService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TaskService::class.java)
            intent.action = ACTION_STOP
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 注册任务状态监听器（onCreate 仅一次，避免 onStartCommand 重复调用造成重复监听）
        TaskManager.addListener(object : TaskManager.TaskListener {
            override fun onTaskStarted(taskId: String, taskName: String) {
                updateNotification()
            }

            override fun onTaskOutput(taskId: String, line: String) {}

            override fun onTaskCompleted(taskId: String) {
                updateNotification()
                checkAndStopIfNeeded()
            }

            override fun onTaskStopped(taskId: String, reason: String) {
                updateNotification()
                checkAndStopIfNeeded()
            }

            override fun onTaskError(taskId: String, error: String) {
                updateNotification()
                checkAndStopIfNeeded()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 跨版本兼容的 stopForeground：
     * - API 33+ 用 int flag (STOP_FOREGROUND_REMOVE)
     * - API <33 用 boolean true 等价于移除通知
     */
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoBOT 后台任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AutoBOT 后台任务运行通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val runningTasks = TaskManager.getRunningTasks()
        val count = runningTasks.size
        val taskName = TaskExecutor.currentTaskName().ifBlank {
            runningTasks.firstOrNull()?.name ?: ""
        }
        val contentText = when {
            count > 0 && taskName.isNotBlank() -> "正在执行：$taskName"
            count > 0 -> "运行中任务数: $count"
            else -> "无运行中任务"
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("AutoBOT")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun checkAndStopIfNeeded() {
        if (!TaskManager.hasRunningTasks()) {
            stopForegroundCompat()
            stopSelf()
        }
    }
}
