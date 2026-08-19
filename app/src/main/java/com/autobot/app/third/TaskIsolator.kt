package com.autobot.app.third

import android.util.Log
import com.autobot.app.util.ShellExecutor

/**
 * 任务隔离器：把目标 App 的 task 从主屏（display 0）移动到 VirtualDisplay 上。
 *
 * 背景：单独使用 `am start --display <vdId>` 启动 App，系统仍可能因为 AMS 调度策略
 * 把目标 activity 拉回主屏（display 0），结果就是 VD 内继续显示 AutoBOT 启动页，
 * 所有 input --display vdId tap 注入的坐标全打到 AutoBOT，而不是目标 App —— 这是
 * 用户观察到的"操作映射在本应用上而不是目标 App"的核心原因。
 *
 * 流程（与项目记忆一致）：
 *   1. 调用方先 `am start --display <vdId>` 启动目标 App
 *   2. 等 ~1.5s（Activity 进入 Resumed 状态，task 在 dumpsys 中可被解析）
 *   3. 执行 `dumpsys activity activities`，解析出所有 task：
 *        - 显示在 display 0 上
 *        - baseActivity / topActivity 匹配目标 pkg
 *        - taskId 可用
 *   4. 先尝试 `am stack move-task <taskId> <vdId>`（Android 10-）
 *      失败则 fallback `am task move-task <taskId> <vdId>`（Android 11+ 新命令）
 *   5. 成功后目标 App 的整个 task stack 被搬到 VD，VD 显示目标 App
 *
 * ★ 结果：脚本中 `input --display $AUTOBOT_VD_DISPLAY_ID tap x y` 会直接命中
 *   目标 App 上对应坐标，行为一致于 MAA-Meow（AutoBOT 主界面位于 display 0 前台，
 *   VD 上只有目标 App 画面和点击注入）
 *
 * 注意：需要 Shizuku 执行 dumpsys / am 命令。
 */
object TaskIsolator {

    private const val TAG = "TaskIsolator"

    /** am start 之后等待 AMS 注册 task 的时间（项目记忆经验值） */
    private const val AMS_SETTLE_MS = 1500L

    /**
     * 将 [packageName] 的 task 从 display 0 移动到 [vdDisplayId]。
     *
     * @param packageName  目标 App 包名（如 com.taobao.taobao）
     * @param vdDisplayId  VirtualDisplay ID（必须 > 0）
     * @param waitSettle   am start 之后额外等待时长（0 表示不重复等待，调用方自己等待过）
     * @param useShizuku   是否通过 Shizuku 执行（必须 true，display 隔离所需命令需 shell 权限）
     * @return 第一元素：是否成功移动（或无需移动）；第二元素：诊断信息（供日志）
     */
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

        // 1. dumpsys 读取所有 activity/task
        val dumpsys = ShellExecutor.execute(
            "dumpsys activity activities",
            useShizuku = useShizuku,
            timeout = 20_000L
        )
        if (!dumpsys.isSuccess) {
            return false to "dumpsys activity activities 失败 exit=${dumpsys.exitCode}"
        }
        val dump = dumpsys.stdout

        // 2. 解析 display 0 上且 base/top activity pkg 匹配的 taskId
        val candidateIds = parseDisplay0TaskIdsOfPackage(dump, packageName)
        if (candidateIds.isEmpty()) {
            return true to "display 0 上未找到 package=$packageName 的残留 task（可能 am start --display 已生效）"
        }

        // 3. 逐个移动到 VD
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
     *
     * dumpsys 解析策略（适配主流 Android 10-14，避免过多依赖 dumpsys 精确格式）：
     *   - 对每个 "Task id=" 或 "TaskRecord{" 片段：
     *       a. 提取 taskId
     *       b. 读取本段直到下一个 Task id= / TaskRecord{ 之前，看是否包含 displayId=0（或未显示 display，非 VD）
     *       c. 看是否含 baseActivity= 或 cmp= 指向 packageName
     *   - 命中即收集
     */
    internal fun parseDisplay0TaskIdsOfPackage(dump: String, packageName: String): List<Int> {
        val result = mutableListOf<Int>()
        if (dump.isBlank()) return result

        // 用正则切分 "Task id=.../TaskRecord{...}" 段
        //   Task id=29924: com.xxx/.Activity ... displayId=0
        //   TaskRecord{239df97 #29924 ... displayId=0} ...
        val lines = dump.lines()
        val pkgKey = packageName
        var currentTaskId: Int? = null
        var currentBlockSb = StringBuilder()

        fun evaluateAndReset() {
            val id = currentTaskId ?: return
            val block = currentBlockSb.toString()

            // 判定 display：优先 displayId=0；未出现 displayId=<非0> 且未出现 mVirtualDisplayId=<非0> 时也视为 display 0
            val hasVdDisplayId = displayRe.find(block)?.destructured?.component1()?.toIntOrNull()?.takeIf { it > 0 } != null
            val matchesPkg = block.contains(pkgKey) // 直接包名片段匹配
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
            // 未出现明确失败签名：命令有效但 stderr 有警告
            return !msg.contains("Unknown command", ignoreCase = true) &&
                    !msg.contains("Usage: am", ignoreCase = true) &&
                    !msg.contains("SecurityException", ignoreCase = true)
        }

        // Android 10- 语法
        val cmd1 = "am stack move-task $taskId $vdDisplayId"
        val r1 = ShellExecutor.execute(cmd1, useShizuku = useShizuku, timeout = 10_000L)
        if (looksOk(r1)) return true to combine(r1)

        // Android 11+ 语法
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
