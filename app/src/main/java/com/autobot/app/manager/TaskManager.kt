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

    // 运行中的任务：taskId -> (TaskInfo, Job, CancelHandle)
    // CancelHandle 用于用户点红色停止按钮时，真正 destroy 正在执行的 sh 子进程
    private val runningTasks = mutableMapOf<String, Triple<TaskInfo, Job, ShellExecutor.CancelHandle>>()

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
     * @param displayId 虚拟显示器 ID（>0 时注入 AUTOBOT_VD_DISPLAY_ID 环境变量，
     *                  脚本中可通过 am start --display $AUTOBOT_VD_DISPLAY_ID 启动 App 到 VD）
     * @param extraEnv 附加环境变量（除 displayId 注入外）
     * @return 任务ID
     */
    fun submitTask(
        name: String,
        command: String,
        type: TaskType = TaskType.COMMAND,
        useShizuku: Boolean = true,
        displayId: Int = -1,
        extraEnv: Map<String, String> = emptyMap()
    ): String {
        // 组合环境变量：displayId > 0 时注入 AUTOBOT_VD_DISPLAY_ID
        val env: Map<String, String> = buildMap {
            putAll(extraEnv)
            if (displayId > 0) {
                put("AUTOBOT_VD_DISPLAY_ID", displayId.toString())
            }
        }
        val task = TaskInfo(
            name = name,
            command = command,
            type = type,
            useShizuku = useShizuku,
            displayId = displayId,
            env = env
        )

        // 每一个任务独立一个取消句柄。外部 stopTask(id) 会调 cancelHandle.cancel()，
        // 让 ShellExecutor.drainProcessStreams 立即 process.destroy 子进程。
        val cancelHandle = ShellExecutor.CancelHandle()

        val job = coroutineScope.launch {
            executeTaskInternal(task, cancelHandle)
        }

        runningTasks[task.id] = Triple(task, job, cancelHandle)
        taskHistory.add(0, task)

        // 通知任务开始
        task.status = TaskStatus.RUNNING
        listeners.forEach { it.onTaskStarted(task) }

        return task.id
    }

    /**
     * 任务执行内部方法
     *
     * 关键：调用 ShellExecutor 流式 API，stdout/stderr 每一行
     *       1. 追加到 task.output 保留历史
     *       2. 通过 TaskListener.onTaskOutput 通知所有观察者（UI 实时日志显示）
     *
     * @param cancel 任务取消句柄：外部 stopTask(taskId) → cancel.cancel() → process.destroy
     */
    private suspend fun executeTaskInternal(task: TaskInfo, cancel: ShellExecutor.CancelHandle) {
        try {
            // 统一给每行加「时间戳 + stdout/stderr」前缀，便于日志观察
            val prefix = "[${task.id.substring(0, 4)}] "
            val onOut: (String) -> Unit = { line ->
                task.output.append("$prefix[OUT] $line\n")
                listeners.forEach { it.onTaskOutput(task, "[OUT] $line") }
            }
            val onErr: (String) -> Unit = { line ->
                task.output.append("$prefix[ERR] $line\n")
                listeners.forEach { it.onTaskOutput(task, "[ERR] $line") }
            }

            val exitCode = when (task.type) {
                TaskType.COMMAND -> {
                    ShellExecutor.executeStreaming(
                        task.command, task.useShizuku, task.env,
                        cancel = cancel,
                        onStdoutLine = onOut,
                        onStderrLine = onErr
                    )
                }
                TaskType.SCRIPT -> {
                    ShellExecutor.executeScriptStreaming(
                        task.command, task.useShizuku, task.env,
                        cancel = cancel,
                        onStdoutLine = onOut,
                        onStderrLine = onErr
                    )
                }
                TaskType.ADB -> {
                    ShellExecutor.executeStreaming(
                        task.command, task.useShizuku, task.env,
                        cancel = cancel,
                        onStdoutLine = onOut,
                        onStderrLine = onErr
                    )
                }
            }

            if (task.status == TaskStatus.STOPPED) {
                return
            }

            // exitCode == -3 是 ShellExecutor 新语义：外部取消（已经 process.destroy 过）
            // 直接按"停止"流程走，不要当成错误。
            if (exitCode == -3 || cancel.isRequested()) {
                task.status = TaskStatus.STOPPED
                task.endTime = System.currentTimeMillis()
                runningTasks.remove(task.id)
                listeners.forEach { it.onTaskStopped(task) }
                return
            }

            task.exitCode = exitCode
            task.status = if (exitCode == 0) TaskStatus.COMPLETED else TaskStatus.ERROR
            task.endTime = System.currentTimeMillis()

            // 从运行中移除
            runningTasks.remove(task.id)

            if (exitCode == 0) {
                listeners.forEach { it.onTaskCompleted(task) }
            } else {
                val msg = "Exit code $exitCode"
                listeners.forEach { it.onTaskError(task, msg) }
            }

        } catch (e: Exception) {
            if (task.status == TaskStatus.STOPPED) {
                return
            }
            // job.cancel() 会抛 CancellationException；此时再配合 cancel.cancel() 状态
            // 说明用户主动停止，按"停止"流程记日志。
            if (cancel.isRequested()) {
                task.status = TaskStatus.STOPPED
                task.endTime = System.currentTimeMillis()
                runningTasks.remove(task.id)
                listeners.forEach { it.onTaskStopped(task) }
                return
            }
            task.status = TaskStatus.ERROR
            task.endTime = System.currentTimeMillis()
            val errLine = "[EXCEPTION] ${e.message ?: "Unknown error"}"
            task.output.append(errLine).append("\n")
            runningTasks.remove(task.id)
            listeners.forEach { it.onTaskOutput(task, errLine) }
            listeners.forEach { it.onTaskError(task, e.message ?: "Unknown error") }
        }
    }

    /**
     * 停止指定任务
     *   1) CancelHandle.cancel() → 立刻 destroy sh Process（脚本子进程也被杀）
     *   2) 再 cancelAndJoin 协程（双重保险）
     */
    fun stopTask(taskId: String): Boolean {
        val triple = runningTasks[taskId] ?: return false
        val (task, job, cancelHandle) = triple

        task.status = TaskStatus.STOPPED
        task.endTime = System.currentTimeMillis()

        // 先让 ShellExecutor 里 process 立刻停（否则只 cancelJob，sh 脚本还会继续跑）
        cancelHandle.cancel()

        coroutineScope.launch {
            try {
                job.cancelAndJoin()
            } catch (_: Exception) {
                // 忽略取消时的异常
            }
            runningTasks.remove(taskId)
            listeners.forEach { it.onTaskStopped(task) }
        }

        return true
    }

    /**
     * 停止所有运行中的任务（UI 上「红色停止」按钮一键停止用）
     * @return 实际终止的任务数量
     */
    fun stopAllTasks(): Int {
        val ids = runningTasks.keys.toList()
        var count = 0
        ids.forEach { if (stopTask(it)) count++ }
        return count
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
