package com.autobot.app.manager

import com.autobot.app.model.TaskInfo
import com.autobot.app.model.TaskStatus
import com.autobot.app.model.TaskType
import com.autobot.app.util.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

/**
 * 后台任务管理器
 * 负责管理和调度后台任务，支持在独立线程中执行 shell 命令/脚本
 */
object TaskManager {

    // 运行中的任务：taskId -> (TaskInfo, Job)
    private val runningTasks = mutableMapOf<String, Pair<TaskInfo, Job>>()

    // 任务历史
    private val taskHistory = mutableListOf<TaskInfo>()

    // 任务状态变化监听器
    private val listeners = mutableListOf<TaskListener>()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * 任务状态监听器接口
     */
    interface TaskListener {
        fun onTaskStarted(task: TaskInfo)
        fun onTaskOutput(task: TaskInfo, line: String)
        fun onTaskCompleted(task: TaskInfo)
        fun onTaskStopped(task: TaskInfo)
        fun onTaskError(task: TaskInfo, error: String)
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

    /**
     * 提交并执行任务
     * @param name 任务名称
     * @param command 命令内容（命令/sh脚本路径/adb命令）
     * @param type 任务类型
     * @param useShizuku 是否使用Shizuku权限
     * @return 任务ID
     */
    fun submitTask(
        name: String,
        command: String,
        type: TaskType = TaskType.COMMAND,
        useShizuku: Boolean = true
    ): String {
        val task = TaskInfo(
            name = name,
            command = command,
            type = type,
            useShizuku = useShizuku
        )

        val job = coroutineScope.launch {
            executeTaskInternal(task)
        }

        runningTasks[task.id] = Pair(task, job)
        taskHistory.add(0, task)

        // 通知任务开始
        task.status = TaskStatus.RUNNING
        listeners.forEach { it.onTaskStarted(task) }

        return task.id
    }

    /**
     * 任务执行内部方法
     */
    private suspend fun executeTaskInternal(task: TaskInfo) {
        try {
            val result = when (task.type) {
                TaskType.COMMAND -> {
                    executeCommandWithOutput(task)
                }
                TaskType.SCRIPT -> {
                    ShellExecutor.executeScript(task.command, task.useShizuku)
                }
                TaskType.ADB -> {
                    ShellExecutor.executeAdbCommand(task.command, useShizuku = task.useShizuku)
                }
            }

            if (task.status == TaskStatus.STOPPED) {
                return
            }

            task.output.append(result.stdout)
            if (result.stderr.isNotEmpty()) {
                task.output.append("\n[STDERR]\n").append(result.stderr)
            }
            task.exitCode = result.exitCode
            task.status = if (result.isSuccess) TaskStatus.COMPLETED else TaskStatus.ERROR
            task.endTime = System.currentTimeMillis()

            // 从运行中移除
            runningTasks.remove(task.id)

            if (result.isSuccess) {
                listeners.forEach { it.onTaskCompleted(task) }
            } else {
                listeners.forEach { it.onTaskError(task, result.stderr) }
            }

        } catch (e: Exception) {
            if (task.status == TaskStatus.STOPPED) {
                return
            }
            task.status = TaskStatus.ERROR
            task.endTime = System.currentTimeMillis()
            task.output.append("\n[EXCEPTION]\n").append(e.message)
            runningTasks.remove(task.id)
            listeners.forEach { it.onTaskError(task, e.message ?: "Unknown error") }
        }
    }

    /**
     * 带实时输出的命令执行（适用于 COMMAND 类型）
     */
    private suspend fun executeCommandWithOutput(task: TaskInfo): ShellExecutor.ShellResult {
        return ShellExecutor.execute(task.command, task.useShizuku, Long.MAX_VALUE)
    }

    /**
     * 停止任务
     */
    fun stopTask(taskId: String): Boolean {
        val pair = runningTasks[taskId] ?: return false
        val (task, job) = pair

        task.status = TaskStatus.STOPPED
        task.endTime = System.currentTimeMillis()

        coroutineScope.launch {
            try {
                job.cancelAndJoin()
            } catch (e: Exception) {
                // 忽略取消时的异常
            }
            runningTasks.remove(taskId)
            listeners.forEach { it.onTaskStopped(task) }
        }

        return true
    }

    /**
     * 获取所有运行中的任务
     */
    fun getRunningTasks(): List<TaskInfo> {
        return runningTasks.values.map { it.first }.toList()
    }

    /**
     * 获取历史任务（包括运行中）
     */
    fun getAllTasks(): List<TaskInfo> {
        return taskHistory.toList()
    }

    /**
     * 获取任务详情
     */
    fun getTask(taskId: String): TaskInfo? {
        return runningTasks[taskId]?.first ?: taskHistory.find { it.id == taskId }
    }

    /**
     * 清空历史任务（保留运行中）
     */
    fun clearHistory() {
        val runningIds = runningTasks.keys.toSet()
        taskHistory.removeAll { it.id !in runningIds }
    }

    /**
     * 是否有运行中的任务
     */
    fun hasRunningTasks(): Boolean {
        return runningTasks.isNotEmpty()
    }
}
