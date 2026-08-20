package com.autobot.app.third

import android.util.Log
import com.autobot.app.util.ShellExecutor

/**
 * 任务隔离器：把目标 App 的 task 从主屏（display 0）移动到 VirtualDisplay 上。
 *
 * 踩坑：单独使用 `am start --display <vdId>` 启动 App，系统仍可能因 AMS 调度策略把
 * 目标 activity 拉回主屏（display 0），导致 input --display vdId tap 注入坐标打到
 * AutoBOT 而非目标 App —— 这是"操作映射在本应用上"的核心原因。
 *
 * 注意：需要 Shizuku 执行 dumpsys / am 命令。
 */
object TaskIsolator {

    private const val TAG = "TaskIsolator"

    /** am start 之后等待 AMS 注册 task 的时间（项目记忆经验值） */
    private const val AMS_SETTLE_MS = 1500L

    /** 将 [packageName] 的 task 从 display 0 移动到 [vdDisplayId]；useShizuku 必须为 true。 */
    fun isolateToVirtualDisplay(
        packageName: String,
        vdDisplayId: Int,
        waitSettle: Long = AMS_SETTLE_MS,
        useShizuku: Boolean = true
    ): Pair<Boolean, String> {
        if (vdDisplayId <= 0) {
            return false to "vdDisplayId=$vdDisplayId 非法，无法隔离任务"
        }
        if (packageName.isBlank()) return false to "packageName 为空"

        if (waitSettle > 0) {
            try {
                Thread.sleep(waitSettle)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val dumpsys = ShellExecutor.execute(
            "dumpsys activity activities",
            useShizuku = useShizuku,
            timeout = 20_000L
        )
        if (!dumpsys.isSuccess) {
            return false to "dumpsys activity activities 失败 exit=${dumpsys.exitCode}"
        }
        val dump = dumpsys.stdout

        val candidateIds = parseDisplay0TaskIdsOfPackage(dump, packageName)
        if (candidateIds.isEmpty()) {
            return true to "display 0 上未找到 package=$packageName 的残留 task（可能 am start --display 已生效）"
        }

        val results = mutableListOf<String>()
        var anyMoved = false
        for (taskId in candidateIds) {
            val r = moveTask(taskId, vdDisplayId, useShizuku)
            if (r.first) anyMoved = true
            results += "taskId=$taskId -> ${r.second}"
        }
        return (anyMoved || candidateIds.isNotEmpty()) to results.joinToString(" ; ")
    }

    /**
     * 解析 dumpsys activity activities 中位于 display 0 且归属 [packageName] 的 taskId。
     * 适配 Android 10-14，不依赖 dumpsys 精确格式。
     */
    internal fun parseDisplay0TaskIdsOfPackage(dump: String, packageName: String): List<Int> {
        val result = mutableListOf<Int>()
        if (dump.isBlank()) return result

        val lines = dump.lines()
        val pkgKey = packageName
        var currentTaskId: Int? = null
        var currentBlockSb = StringBuilder()

        fun evaluateAndReset() {
            val id = currentTaskId ?: return
            val block = currentBlockSb.toString()

            val hasVdDisplayId = displayRe.find(block)?.destructured?.component1()?.toIntOrNull()?.takeIf { it > 0 } != null
            val matchesPkg = block.contains(pkgKey)
            val inDisplay0 = !hasVdDisplayId ||
                    displayRe.find(block)?.destructured?.component1()?.toIntOrNull() == 0
            if (inDisplay0 && matchesPkg) {
                result += id
            }
            currentTaskId = null
            currentBlockSb = StringBuilder()
        }

        for (line in lines) {
            val mId = taskIdRe.find(line)
            if (mId != null) {
                evaluateAndReset()
                currentTaskId = mId.groupValues[1].toIntOrNull()
                    ?: mId.groupValues.getOrNull(2)?.toIntOrNull()
            }
            if (currentTaskId != null) currentBlockSb.append(line).append('\n')
        }
        evaluateAndReset()
        return result
    }

    /** 匹配 "Task id=123:" 或 "TaskRecord{xxx #123 ...}" 中的 taskId 数字 */
    private val taskIdRe =
        Regex("""(?:Task\s+id=(\d+))|(?:TaskRecord\{[^#]*#(\d+))""", RegexOption.IGNORE_CASE)

    /** 匹配 displayId=12 / mVirtualDisplayId=12 中的数字 */
    private val displayRe =
        Regex("""(?:displayId|mVirtualDisplayId)\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * 用 am 命令把 task 移到 displayId：优先 Android 10- 的 stack 子命令，
     * 失败 fallback 到 Android 11+ 的 task 子命令。
     */
    private fun moveTask(taskId: Int, vdDisplayId: Int, useShizuku: Boolean): Pair<Boolean, String> {
        fun combine(r: ShellExecutor.ShellResult): String =
            listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString(" ; ")

        fun looksOk(r: ShellExecutor.ShellResult): Boolean {
            if (r.isSuccess) return true
            val msg = combine(r)
            return !msg.contains("Unknown command", ignoreCase = true) &&
                    !msg.contains("Usage: am", ignoreCase = true) &&
                    !msg.contains("SecurityException", ignoreCase = true)
        }

        val cmd1 = "am stack move-task $taskId $vdDisplayId"
        val r1 = ShellExecutor.execute(cmd1, useShizuku = useShizuku, timeout = 10_000L)
        if (looksOk(r1)) return true to combine(r1)

        val cmd2 = "am task move-task $taskId $vdDisplayId"
        val r2 = ShellExecutor.execute(cmd2, useShizuku = useShizuku, timeout = 10_000L)
        return if (looksOk(r2)) {
            true to combine(r2)
        } else {
            Log.e(TAG,
                "moveTask failed: stack & task both returned errors. " +
                        "stack=\"${combine(r1)}\" ; task=\"${combine(r2)}\"")
            false to "stack/task subcommands both failed"
        }
    }
}
