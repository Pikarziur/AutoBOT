package com.autobot.app.manager

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object TaskManager {

    private const val TAG = "TaskManager"
    private const val LOG_MAX_LINES = 500

    private val runningTasks = mutableMapOf<String, RunningTaskInfo>()

    private val taskHistory = mutableListOf<RunningTaskInfo>()

    private val listeners = mutableListOf<TaskListener>()

    private val logBuffer = mutableListOf<String>()
    private val logLock = Any()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

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

    enum class TaskStatus {
        RUNNING,
        COMPLETED,
        STOPPED,
        ERROR
    }

    interface TaskListener {
        fun onTaskStarted(taskId: String, taskName: String)
        fun onTaskOutput(taskId: String, line: String)
        fun onTaskCompleted(taskId: String)
        fun onTaskStopped(taskId: String, reason: String)
        fun onTaskError(taskId: String, error: String)
    }

    fun addListener(listener: TaskListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: TaskListener) {
        listeners.remove(listener)
    }

    fun notifyStarted(taskId: String, taskName: String) {
        val info = RunningTaskInfo(id = taskId, name = taskName)
        synchronized(runningTasks) {
            runningTasks[taskId] = info
            taskHistory.add(0, info)
        }
        Log.i(TAG, "Task started: $taskName (id=$taskId)")
        listeners.forEach { runCatching { it.onTaskStarted(taskId, taskName) } }
    }

    fun notifyOutput(taskId: String, line: String) {
        synchronized(logLock) {
            logBuffer.add(line)
            while (logBuffer.size > LOG_MAX_LINES) {
                logBuffer.removeAt(0)
            }
        }
        listeners.forEach { runCatching { it.onTaskOutput(taskId, line) } }
    }

    fun notifyCompleted(taskId: String) {
        val info = synchronized(runningTasks) {
            runningTasks.remove(taskId)?.let {
                it.copy(status = TaskStatus.COMPLETED, endTimeMs = System.currentTimeMillis())
            }?.also { updated ->
                val idx = taskHistory.indexOfFirst { it.id == taskId }
                if (idx >= 0) taskHistory[idx] = updated
            }
        } ?: return
        Log.i(TAG, "Task completed: ${info.name} (dur=${info.getDurationText()})")
        listeners.forEach { runCatching { it.onTaskCompleted(taskId) } }
    }

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

    fun getRunningTasks(): List<RunningTaskInfo> {
        return synchronized(runningTasks) { runningTasks.values.toList() }
    }

    fun hasRunningTasks(): Boolean {
        return synchronized(runningTasks) { runningTasks.isNotEmpty() }
    }

    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.clear()
        }
    }

    fun getLogs(): List<String> {
        return synchronized(logLock) { logBuffer.toList() }
    }

    fun getAllTasks(): List<RunningTaskInfo> {
        return synchronized(runningTasks) { taskHistory.toList() }
    }
}
