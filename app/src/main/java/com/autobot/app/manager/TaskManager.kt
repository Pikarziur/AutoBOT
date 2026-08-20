package com.autobot.app.manager

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 任务状态跟踪 hub（替代旧版 SH 脚本进程管理器）
 *
 * ★重要变更（与旧版差异）★：
 *   旧版负责 Shizuku.newProcess 启 sh 子进程跑 .sh 脚本，submitTask(type=SCRIPT) 走
 *   ShellExecutor.executeScriptStreaming。新版 adb shell 路径已全部删除，
 *   任务动作由 [TaskExecutor] 在 App 进程内通过 CompositionService 直接驱动
 *   IInputManager.injectInputEvent 注入到 VD。
 *
 * 现职责（精简版状态 hub）：
 *   1. 维护"当前正在执行的任务" id/name + 输出日志缓冲
 *   2. 通过 [TaskListener] 把任务生命周期事件通知给 UI 与前台服务 [com.autobot.app.service.TaskService]
 *   3. 提供 [stopAllTasks] 给 UI 红色停止按钮调用 → 转发到 [TaskExecutor.stop]
 *
 * 不再持有 Process / CancelHandle / sh 子进程。
 *
 * 协作方：
 *   - [TaskExecutor]：调用 [notifyStarted]/[notifyOutput]/[notifyCompleted]/[notifyStopped]/[notifyError]
 *     上报任务状态
 *   - [com.autobot.app.ui.tasks.MonitorViewModel]：注册 listener 把状态推给 UI 按钮变形 + 日志区
 *   - [com.autobot.app.service.TaskService]：注册 listener 在有任务时启动前台服务保活
 */
object TaskManager {

    private const val TAG = "TaskManager"
    private const val LOG_MAX_LINES = 500

    /** 当前正在执行的任务：taskId → 简单状态记录 */
    private val runningTasks = mutableMapOf<String, RunningTaskInfo>()

    /** 任务历史（最近 N 条，仅用于状态查询，不再保留 stdout 全量） */
    private val taskHistory = mutableListOf<RunningTaskInfo>()

    /** 任务状态变化监听器（UI + 前台服务各注册一份） */
    private val listeners = mutableListOf<TaskListener>()

    /** 日志缓冲：所有任务的合并输出（UI 日志 Tab 显示） */
    private val logBuffer = mutableListOf<String>()
    private val logLock = Any()

    /** 后台日志清理协程作用域 */
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * 当前运行中的任务信息
     *
     * @param id          任务 ID（= TaskFile.id）
     * @param name        任务展示名（用于通知栏文案）
     * @param startTimeMs 启动时间戳
     */
    data class RunningTaskInfo(
        val id: String,
        val name: String,
        val startTimeMs: Long = System.currentTimeMillis(),
        var endTimeMs: Long? = null,
        var status: TaskStatus = TaskStatus.RUNNING,
        var errorMessage: String? = null
    ) {
        fun getDurationText(): String {
            val end = endTimeMs ?: System.currentTimeMillis()
            val diff = end - startTimeMs
            val s = diff / 1000
            val m = s / 60
            val h = m / 60
            return when {
                h > 0 -> "${h}h ${m % 60}m ${s % 60}s"
                m > 0 -> "${m}m ${s % 60}s"
                else -> "${s}s"
            }
        }
    }

    /**
     * 任务状态枚举
     */
    enum class TaskStatus {
        RUNNING,     // 运行中
        COMPLETED,   // 已完成
        STOPPED,     // 已停止
        ERROR        // 出错
    }

    /**
     * 任务状态监听器接口
     *
     * 由 [MonitorViewModel]（UI 按钮状态 + 日志 Tab）与 [TaskService]（前台服务保活）实现。
     */
    interface TaskListener {
        fun onTaskStarted(taskId: String, taskName: String)
        fun onTaskOutput(taskId: String, line: String)
        fun onTaskCompleted(taskId: String)
        fun onTaskStopped(taskId: String, reason: String)
        fun onTaskError(taskId: String, error: String)
    }

    /**
     * 添加任务监听器
     */
    fun addListener(listener: TaskListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 移除任务监听器
     */
    fun removeListener(listener: TaskListener) {
        listeners.remove(listener)
    }

    // ===== TaskExecutor 上报入口 =====

    /**
     * 任务开始通知（由 TaskExecutor.submit 调用）
     *
     * - 记录到 runningTasks + taskHistory
     * - 通知所有 listener（UI 按钮变红 + 前台服务启动）
     */
    fun notifyStarted(taskId: String, taskName: String) {
        val info = RunningTaskInfo(id = taskId, name = taskName)
        synchronized(runningTasks) {
            runningTasks[taskId] = info
            taskHistory.add(0, info)
        }
        Log.i(TAG, "Task started: $taskName (id=$taskId)")
        listeners.forEach { runCatching { it.onTaskStarted(taskId, taskName) } }
    }

    /**
     * 任务输出日志通知（由 TaskExecutor 在每个 action 执行前后调用）
     *
     * - 追加到 logBuffer（最多保留 LOG_MAX_LINES 行）
     * - 通知所有 listener 实时刷新 UI 日志区
     */
    fun notifyOutput(taskId: String, line: String) {
        synchronized(logLock) {
            logBuffer.add(line)
            while (logBuffer.size > LOG_MAX_LINES) {
                logBuffer.removeAt(0)
            }
        }
        listeners.forEach { runCatching { it.onTaskOutput(taskId, line) } }
    }

    /**
     * 任务完成通知
     */
    fun notifyCompleted(taskId: String) {
        val info = synchronized(runningTasks) {
            runningTasks.remove(taskId)?.let {
                it.copy(status = TaskStatus.COMPLETED, endTimeMs = System.currentTimeMillis())
            }?.also { updated ->
                // 同步更新 history 第一条（如果有）
                val idx = taskHistory.indexOfFirst { it.id == taskId }
                if (idx >= 0) taskHistory[idx] = updated
            }
        } ?: return
        Log.i(TAG, "Task completed: ${info.name} (dur=${info.getDurationText()})")
        listeners.forEach { runCatching { it.onTaskCompleted(taskId) } }
    }

    /**
     * 任务停止通知（用户主动点红色停止按钮）
     */
    fun notifyStopped(taskId: String, reason: String) {
        val info = synchronized(runningTasks) {
            runningTasks.remove(taskId)?.let {
                it.copy(status = TaskStatus.STOPPED, endTimeMs = System.currentTimeMillis(), errorMessage = reason)
            }?.also { updated ->
                val idx = taskHistory.indexOfFirst { it.id == taskId }
                if (idx >= 0) taskHistory[idx] = updated
            }
        } ?: return
        Log.i(TAG, "Task stopped: ${info.name} (reason=$reason)")
        listeners.forEach { runCatching { it.onTaskStopped(taskId, reason) } }
    }

    /**
     * 任务出错通知
     */
    fun notifyError(taskId: String, error: String) {
        val info = synchronized(runningTasks) {
            runningTasks.remove(taskId)?.let {
                it.copy(status = TaskStatus.ERROR, endTimeMs = System.currentTimeMillis(), errorMessage = error)
            }?.also { updated ->
                val idx = taskHistory.indexOfFirst { it.id == taskId }
                if (idx >= 0) taskHistory[idx] = updated
            }
        } ?: return
        Log.e(TAG, "Task error: ${info.name} (err=$error)")
        listeners.forEach { runCatching { it.onTaskError(taskId, error) } }
    }

    /**
     * 停止所有运行中的任务（UI 红色「停止」按钮一键停止用）
     *
     * 转发到 [TaskExecutor.stop]（新版不再持有 sh Process，必须由 TaskExecutor 协程级取消）
     *
     * @return 实际终止的任务数量
     */
    fun stopAllTasks(): Int {
        val count: Int
        synchronized(runningTasks) {
            count = runningTasks.size
        }
        if (count > 0) {
            TaskExecutor.stop()
        }
        return count
    }

    /**
     * 获取所有运行中的任务（用于前台服务通知文案）
     */
    fun getRunningTasks(): List<RunningTaskInfo> {
        return synchronized(runningTasks) { runningTasks.values.toList() }
    }

    /**
     * 是否有运行中的任务
     */
    fun hasRunningTasks(): Boolean {
        return synchronized(runningTasks) { runningTasks.isNotEmpty() }
    }

    /**
     * 清空日志缓冲（UI「清空」按钮调用）
     */
    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.clear()
        }
    }

    /**
     * 获取日志缓冲快照（UI 启动时恢复显示用）
     */
    fun getLogs(): List<String> {
        return synchronized(logLock) { logBuffer.toList() }
    }

    /**
     * 获取历史任务（包括运行中）—— 仅供扩展用，UI 当前未展示
     */
    fun getAllTasks(): List<RunningTaskInfo> {
        return synchronized(runningTasks) { taskHistory.toList() }
    }
}
