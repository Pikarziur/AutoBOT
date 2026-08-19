package com.autobot.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 后台任务数据模型
 */
data class TaskInfo(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val name: String,
    val command: String,
    val useShizuku: Boolean = true,
    val type: TaskType = TaskType.COMMAND,
    /**
     * 虚拟显示器 ID（>0 时注入 AUTOBOT_VD_DISPLAY_ID 环境变量，
     * 让 .sh 脚本中 am / input 等命令只映射到 VD 执行）
     */
    val displayId: Int = -1,
    /**
     * 注入到 sh -c 命令前的额外环境变量（export K=V; 形式）。
     * 除 displayId 自动注入的 AUTOBOT_VD_DISPLAY_ID 外都在这里。
     */
    val env: Map<String, String> = emptyMap(),
    // 运行过程中动态更新：status/endTime/exitCode/output 均为 var
    var status: TaskStatus = TaskStatus.PENDING,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    var output: StringBuilder = StringBuilder(),
    var exitCode: Int? = null
) {
    fun getStartTimeText(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(startTime))
    }

    fun getDurationText(): String {
        val end = endTime ?: System.currentTimeMillis()
        val diff = end - startTime
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}

/**
 * 任务类型
 */
enum class TaskType {
    COMMAND,      // 单条命令
    SCRIPT,       // sh 脚本
    ADB           // ADB 命令
}

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,      // 等待中
    RUNNING,      // 运行中
    COMPLETED,    // 已完成
    STOPPED,      // 已停止
    ERROR         // 出错
}
