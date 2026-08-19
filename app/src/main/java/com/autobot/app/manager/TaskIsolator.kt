package com.autobot.app.manager

import android.util.Log
import com.autobot.app.util.ShellExecutor

/**
 * 任务隔离器
 *
 * 用途：在 App 启动到虚拟显示器后，将真实屏幕（display 0）上残留的目标 App 任务
 * 通过 `am stack move-task` 移动到虚拟显示器所在 stack，避免后台/最近任务列表
 * 出现白屏残留窗口。
 *
 * 起因：淘宝等商业 App 启动流程复杂（Splash → MainActivity 多 Activity 跳转、
 * 多进程组件、`singleTask` 启动模式等），`am start --display <id>` 仅指定初始
 * display，App 部分组件仍可能在真实屏幕创建窗口，导致白屏残留。
 *
 * 策略：
 *   1. App 启动后等待 ~1.5s 让组件初始化稳定
 *   2. 执行 `dumpsys activity activities` 解析出所有 Task 的 (taskId, displayId, packageName)
 *   3. 找出 VD 所在 stackId（VD displayId 上第一个 Task 的 id）
 *   4. 对每个 displayId != VD displayId 且 packageName = 目标 App 的 Task，
 *      执行 `am stack move-task <taskId> <stackId>` 移到 VD
 *
 * 失败处理：move-task 失败仅记录日志，不影响主启动流程。
 * 兼容性：MIUI/HarmonyOS 隐藏 API 过滤可能拒绝 move-task，此时隔离不生效，
 *   但 App 仍能正常在 VD 上运行，仅白屏残留在真实屏幕。
 */
object TaskIsolator {

    private const val TAG = "TaskIsolator"

    /**
     * 任务信息：taskId + 所在 display + 所属 package
     */
    data class TaskInfo(
        val taskId: Int,
        val displayId: Int,
        val packageName: String
    )

    /**
     * 隔离结果
     *
     * @param migratedCount 成功迁移的任务数
     * @param totalFound    发现的目标 App 在非 VD 上的任务总数
     * @param vdStackId     VD 的 stackId（用于诊断，<=0 表示未找到）
     * @param errors        失败的任务列表与错误信息
     */
    data class IsolateResult(
        val migratedCount: Int,
        val totalFound: Int,
        val vdStackId: Int,
        val errors: List<String>
    ) {
        /** 是否完全成功（无残留 或 全部已迁移） */
        val isSuccess: Boolean get() = totalFound == 0 || (migratedCount == totalFound && errors.isEmpty())
    }

    /**
     * 执行任务隔离
     *
     * @param targetPackage 目标 App 包名（如 com.taobao.taobao）
     * @param vdDisplayId   虚拟显示器 ID（必须 > 0）
     * @param delayMs       App 启动后等待时间，让组件初始化稳定（默认 1500ms）
     * @return IsolateResult
     */
    fun isolateToVirtualDisplay(
        targetPackage: String,
        vdDisplayId: Int,
        delayMs: Long = 1500L
    ): IsolateResult {
        if (targetPackage.isBlank() || vdDisplayId <= 0) {
            return IsolateResult(0, 0, 0, listOf("Invalid args: package=$targetPackage, vdId=$vdDisplayId"))
        }
        if (!ShizukuManager.isShizukuGranted()) {
            return IsolateResult(0, 0, 0, listOf("Shizuku not granted"))
        }

        // 1. 等待 App 启动稳定（Splash → MainActivity 跳转通常 1-2s 完成）
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return IsolateResult(0, 0, 0, listOf("Interrupted"))
        }

        // 2. 解析所有任务信息
        val allTasks = parseAllTaskInfos()
        if (allTasks.isEmpty()) {
            val msg = "No tasks parsed from dumpsys (parsing may have failed)"
            Log.w(TAG, msg)
            return IsolateResult(0, 0, 0, listOf(msg))
        }

        // 3. 找 VD 的 stackId（VD displayId 上第一个 Task 的 id 作为目标 stack）
        // Android 10+ 的 RootTask/Task id 即可作为 move-task 的目标 stackId
        val vdStackId = allTasks.firstOrNull { it.displayId == vdDisplayId }?.taskId ?: -1
        if (vdStackId <= 0) {
            val msg = "No task found on VD display=$vdDisplayId (App may not have started yet)"
            Log.w(TAG, msg)
            return IsolateResult(0, 0, 0, listOf(msg))
        }

        // 4. 筛选需要迁移的任务：display != VD 且 packageName 匹配
        val toMigrate = allTasks.filter {
            it.packageName == targetPackage && it.displayId != vdDisplayId
        }

        if (toMigrate.isEmpty()) {
            Log.i(TAG, "No residual tasks of $targetPackage on non-VD displays")
            return IsolateResult(0, 0, vdStackId, emptyList())
        }

        // 5. 逐个迁移到 VD stack
        var migrated = 0
        val errors = mutableListOf<String>()
        for (task in toMigrate) {
            val ok = moveTaskToStack(task.taskId, vdStackId)
            if (ok) {
                migrated++
                Log.i(TAG, "Migrated task ${task.taskId} (display=${task.displayId}, " +
                        "pkg=${task.packageName}) -> stack $vdStackId")
            } else {
                val errMsg = "task=${task.taskId} display=${task.displayId}"
                errors.add(errMsg)
                Log.w(TAG, "Move failed for $errMsg")
            }
        }

        Log.i(TAG, "Isolate result: migrated=$migrated/${toMigrate.size}, vdStackId=$vdStackId")
        return IsolateResult(migrated, toMigrate.size, vdStackId, errors)
    }

    /**
     * 移动单个 task 到指定 stack
     *
     * 命令兼容策略：
     *   - 优先 `am stack move-task`（Android 8+ 通用）
     *   - 失败则尝试 `am task move-task`（Android 11+ 别名）
     *
     * 注意：MIUI/HyperOS 可能拒绝 shell uid 调用此命令，返回错误但不会崩溃。
     *
     * @return true 表示成功移动
     */
    private fun moveTaskToStack(taskId: Int, stackId: Int): Boolean {
        // 尝试 1: am stack move-task
        val cmd1 = "am stack move-task $taskId $stackId"
        val r1 = ShellExecutor.execute(cmd1, useShizuku = true, timeout = 3000)
        if (r1.isSuccess) return true

        // 尝试 2: am task move-task (Android 11+ 别名)
        val cmd2 = "am task move-task $taskId $stackId"
        val r2 = ShellExecutor.execute(cmd2, useShizuku = true, timeout = 3000)
        if (r2.isSuccess) return true

        Log.w(TAG, "Both move commands failed for task $taskId -> stack $stackId: " +
                "stack='${r1.stderr}', task='${r2.stderr}'")
        return false
    }

    /**
     * 解析 `dumpsys activity activities` 输出，提取所有 Task 的 (taskId, displayId, packageName)
     *
     * 兼容多种 Android 版本的输出格式：
     *   - Android 8/9: "Display #0:" + "Task id=42" + "TaskRecord{... A=com.taobao.taobao/.MainActivity}"
     *   - Android 10/11: "Display #0 (id=0)" + "Task{<hex> #42" + "ActivityRecord{... com.taobao.taobao/.MainActivity}"
     *   - Android 12+: "Display #0" + "RootTask{id=42" / "Task{<hex> #42" + package reference
     *
     * 算法：
     *   1. 逐行扫描，跟踪"当前 displayId"和"当前 taskId"
     *   2. 遇到含 package 的行（如 "A=10000:com.taobao.taobao/.MainActivity" 或
     *      "TaskRecord{... A=com.taobao.taobao/.MainActivity}"），提取包名并关联 (displayId, taskId)
     *   3. 按 (taskId, displayId) 去重
     *
     * @return 任务信息列表（可能为空，表示解析失败或无任务）
     */
    private fun parseAllTaskInfos(): List<TaskInfo> {
        val result = ShellExecutor.execute(
            "dumpsys activity activities",
            useShizuku = true,
            timeout = 5000
        )
        if (!result.isSuccess) {
            Log.w(TAG, "dumpsys activity activities failed: ${result.stderr}")
            return emptyList()
        }

        // 包名匹配：形如 "com.foo.bar/.SomeActivity" 或 "A=10000:com.foo.bar/.SomeActivity"
        // 要求至少两个点号（com.foo.bar），避免误匹配文件路径
        val pkgRegex = Regex("""([a-zA-Z][\w]*(?:\.[\w]+){1,})/\.?[a-zA-Z]""")

        // display 匹配：形如 "Display #0" 或 "Display #0 (id=0)"
        val displayRegex = Regex("""Display\s+#(\d+)""")

        // taskId 匹配（多格式兼容）：
        //   - "Task id=42"             (Android 8/9)
        //   - "Task{<hex> #42"         (Android 10+)
        //   - "RootTask{id=42"         (Android 12+)
        //   - "TaskRecord{... #42"     (旧版 TaskRecord 行内带 id)
        val taskIdRegexes = listOf(
            Regex("""Task\s+id=(\d+)"""),
            Regex("""Task\{[^\}]*?\s#(\d+)"""),
            Regex("""RootTask\{[^\}]*?id=(\d+)"""),
            Regex("""TaskRecord\{[^\}]*?\s#(\d+)""")
        )

        val tasks = mutableListOf<TaskInfo>()
        val seen = mutableSetOf<Pair<Int, Int>>()  // (taskId, displayId) 去重

        var currentDisplayId = -1
        var currentTaskId = -1

        for (line in result.stdout.lines()) {
            // 更新 currentDisplayId
            displayRegex.find(line)?.let { m ->
                currentDisplayId = m.groupValues[1].toIntOrNull() ?: -1
                // 注意：不重置 currentTaskId，因为有些格式 display 和 task 可能在不同行
            }
            // 更新 currentTaskId（多格式尝试，第一个匹配即用）
            for (regex in taskIdRegexes) {
                regex.find(line)?.let { m ->
                    currentTaskId = m.groupValues[1].toIntOrNull() ?: -1
                    break
                }
            }
            // 检查是否含包名引用
            val pkgMatch = pkgRegex.find(line)
            if (pkgMatch != null && currentTaskId > 0 && currentDisplayId >= 0) {
                val pkg = pkgMatch.groupValues[1]
                val key = currentTaskId to currentDisplayId
                if (seen.add(key)) {
                    tasks.add(TaskInfo(currentTaskId, currentDisplayId, pkg))
                }
            }
        }

        Log.i(TAG, "Parsed ${tasks.size} task entries from dumpsys")
        return tasks
    }
}
