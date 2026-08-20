package com.autobot.app.manager

import com.autobot.app.model.ProgramAction
import com.autobot.app.model.ProgramActionType
import com.autobot.app.model.ProgramTask
import com.autobot.app.model.TaskAction
import com.autobot.app.model.TaskActionType
import com.autobot.app.model.TaskFile
import com.autobot.app.service.CompositionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务执行器（替代旧版 adb shell / `input tap` 路径）
 *
 * 设计与 MAA-Meow 一致：
 *   - 不通过 Shizuku 启 shell 跑 .sh
 *   - 而是在 App 进程内按 [TaskFile.actions] 顺序，
 *     调用 [CompositionService.injectTouchDown/Move/Up] 与 [CompositionService.injectBack]
 *     把 MotionEvent 通过 stdin pipe 下发给 server 进程；
 *     server 端用 IInputManager.injectInputEvent + setDisplayId 直接注入到 VD。
 *
 * 日志风格：每个动作打印 [N/total] + emoji + 类型 + 参数 + ✓完成耗时
 *           等待打印总时长，程序化任务每组用分隔线突出分组进度
 */
object TaskExecutor {

    private const val TAG = "TaskExecutor"

    /** swipe 动作插值步数：500ms 分 16 步 ≈ 30fps，与 VD 默认帧率一致 */
    private const val SWIPE_STEPS = 16

    /** 当前正在执行的任务取消标志 */
    private val cancelFlag = AtomicBoolean(false)

    /** 当前任务协程 */
    @Volatile
    private var runningJob: Job? = null

    /** 当前正在执行的任务 id（用于日志/通知） */
    @Volatile
    private var currentTaskId: String = ""

    /** 当前正在执行的任务名（供通知栏文案） */
    @Volatile
    private var currentTaskName: String = ""

    /** 是否正在执行（供 UI 按钮变形查询） */
    fun isExecuting(): Boolean = runningJob?.isActive == true

    /** 当前任务名（供通知栏文案） */
    fun currentTaskName(): String = currentTaskName

    /**
     * 提交并执行任务
     *
     * @param taskFile          要执行的任务文件
     * @param compositionService 已启动的 VD 合成服务（displayId 必须 > 0）
     * @param scope             协程作用域（一般传 TaskManager 的 IO scope）
     * @param onLog             日志回调（每行一条，IO 线程调用）
     * @return 任务 ID（= taskFile.id），用于外部 [stop] 引用
     */
    fun submit(
        taskFile: TaskFile,
        compositionService: CompositionService,
        scope: CoroutineScope,
        onLog: (String) -> Unit
    ): String {
        if (isExecuting()) {
            onLog("⚠️  已有任务正在执行，请先停止")
            return currentTaskId
        }

        currentTaskId = taskFile.id
        currentTaskName = taskFile.name
        cancelFlag.set(false)

        TaskManager.notifyStarted(taskFile.id, taskFile.name)

        runningJob = scope.launch(Dispatchers.IO) {
            // ── 启动横幅 ──
            val program = taskFile.program
            val actionCount = if (program != null) program.groups * program.actions.size
                              else taskFile.actions.size
            val taskStartMs = System.currentTimeMillis()
            onLog("")
            onLog("╔══════════════════════════════════════════╗")
            onLog("║ 🚀 任务启动：${taskFile.name.padEnd(24).take(24)} ║")
            onLog("║ 📊 动作总数：${"$actionCount".padEnd(24).take(24)} ║")
            onLog("║ 📺 VD 尺寸：${"${compositionService.width}×${compositionService.height}".padEnd(24).take(24)} ║")
            onLog("║ 🆔 displayId：${"${compositionService.displayId}".padEnd(23).take(23)} ║")
            onLog("╚══════════════════════════════════════════╝")

            try {
                if (program != null) {
                    executeProgram(program, compositionService, onLog)
                } else {
                    executeActions(taskFile.actions, compositionService, onLog)
                }
                if (cancelFlag.get()) {
                    onLog("")
                    onLog("⏹  任务『${taskFile.name}』已被用户停止")
                    onLog(logTotalDuration(taskStartMs, "停止"))
                    TaskManager.notifyStopped(taskFile.id, "用户停止")
                } else {
                    onLog("")
                    onLog("✅ 任务『${taskFile.name}』全部完成")
                    onLog(logTotalDuration(taskStartMs, "完成"))
                    TaskManager.notifyCompleted(taskFile.id)
                }
            } catch (e: Exception) {
                if (cancelFlag.get()) {
                    onLog("")
                    onLog("⏹  任务『${taskFile.name}』已被用户停止")
                    onLog(logTotalDuration(taskStartMs, "停止"))
                    TaskManager.notifyStopped(taskFile.id, "用户停止")
                } else {
                    onLog("")
                    onLog("❌ 任务出错：${e.javaClass.simpleName}: ${e.message}")
                    onLog(logTotalDuration(taskStartMs, "出错"))
                    TaskManager.notifyError(taskFile.id, e.message ?: "Unknown error")
                }
            } finally {
                runningJob = null
            }
        }
        return taskFile.id
    }

    /**
     * 停止当前正在执行的任务
     *
     * 两步保险：
     *   1) cancelFlag.set(true) → 让动作序列在下个 sleep/插值点检查并退出
     *   2) runningJob.cancelAndJoin() → 协程级取消
     */
    fun stop() {
        if (!isExecuting()) return
        cancelFlag.set(true)
        runningJob?.let { job ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    job.cancelAndJoin()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ===== 静态任务执行 =====

    /**
     * 顺序执行 action 列表
     *
     * - 每步执行前检查 [cancelFlag]，被取消时直接返回
     * - WAIT 用 Thread.sleep（在 IO 协程内，不阻塞主线程）
     * - SWIPE 在 durationMs 内插值 SWIPE_STEPS 步 MOVE
     */
    private fun executeActions(
        actions: List<TaskAction>,
        compositionService: CompositionService,
        onLog: (String) -> Unit
    ) {
        val total = actions.size
        for ((index, action) in actions.withIndex()) {
            if (cancelFlag.get()) return
            executeAction(index + 1, total, action, compositionService, onLog)
        }
    }

    private fun executeAction(
        idx: Int,
        total: Int,
        action: TaskAction,
        compositionService: CompositionService,
        onLog: (String) -> Unit
    ) {
        val tag = "[$idx/$total]"
        val startMs = System.currentTimeMillis()
        when (action.type) {
            TaskActionType.TAP -> {
                onLog("$tag 👆 点击 (${action.x}, ${action.y})")
                compositionService.injectTouchDown(action.x, action.y)
                sleepInterruptible(50L)  // 按住 50ms 模拟真人
                compositionService.injectTouchUp(action.x, action.y)
                onLog("     ✓ 完成 · 耗时 ${System.currentTimeMillis() - startMs}ms")
            }
            TaskActionType.SWIPE -> {
                onLog("$tag ↔  滑动 (${action.x}, ${action.y}) → (${action.endX}, ${action.endY}) · ${action.durationMs}ms")
                doSwipe(action.x, action.y, action.endX, action.endY,
                        action.durationMs, null, null, compositionService)
                onLog("     ✓ 完成 · 耗时 ${System.currentTimeMillis() - startMs}ms")
            }
            TaskActionType.WAIT -> {
                onLog("$tag ⏳ 等待 ${action.ms}ms ...")
                sleepInterruptible(action.ms)
                onLog("     ✓ 等待结束 · 实际 ${System.currentTimeMillis() - startMs}ms")
            }
            TaskActionType.BACK -> {
                onLog("$tag 🔙 返回键")
                compositionService.injectBack()
                sleepInterruptible(80L)
                onLog("     ✓ 完成 · 耗时 ${System.currentTimeMillis() - startMs}ms")
            }
        }
    }

    // ===== 程序化任务（program）执行：循环 + 随机 + 分组 + 圆弧 =====

    /**
     * 执行程序化任务
     *
     * 流程：
     *   1. 按 VD 宽高把 [ProgramTask.coordRange] 比例换算成像素范围
     *   2. 外层循环 [ProgramTask.groups] 次，每次为"一组"
     *   3. 每组复制 actions，[ProgramTask.shuffleGroup] 时随机排序
     *   4. 组内逐个执行，动作间按 [ProgramTask.delayMinMs]~[ProgramTask.delayMaxMs] 随机等待
     *   5. 组间同样随机等待
     */
    private fun executeProgram(
        program: ProgramTask,
        cs: CompositionService,
        onLog: (String) -> Unit
    ) {
        val vdW = cs.width
        val vdH = cs.height
        val range = program.coordRange
        val xMin = (vdW * range.xMinRatio).toInt().coerceIn(0, vdW)
        val yMin = (vdH * range.yMinRatio).toInt().coerceIn(0, vdH)
        val xMax = (vdW * range.xMaxRatio).toInt().coerceIn(xMin, vdW)
        val yMax = (vdH * range.yMaxRatio).toInt().coerceIn(yMin, vdH)
        val rnd = kotlin.random.Random

        onLog("📋 程序化配置：${program.groups}组 × ${program.actions.size}动作")
        onLog("   ↳ 延迟范围：${program.delayMinMs}~${program.delayMaxMs}ms")
        onLog("   ↳ 坐标范围：($xMin,$yMin) ~ ($xMax,$yMax)")
        onLog("   ↳ 组内打乱：${if (program.shuffleGroup) "是" else "否"}")
        onLog("")

        for (g in 1..program.groups) {
            if (cancelFlag.get()) return

            // 组内动作：复制一份，可随机排序
            val groupActions = program.actions.toMutableList()
            if (program.shuffleGroup) groupActions.shuffle(rnd)

            // ── 分组横幅 ──
            onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            onLog("🔄 第 $g/${program.groups} 组")
            onLog("   ↳ 执行顺序：${
                groupActions.joinToString(" → ") { it.label.ifBlank { it.type.name } }}")
            onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            for ((idx, act) in groupActions.withIndex()) {
                if (cancelFlag.get()) return
                // 组内动作间随机延迟（首个动作不等）
                if (program.delayBetweenActions && idx > 0) {
                    val delay = rnd.nextLong(program.delayMinMs, program.delayMaxMs + 1)
                    logWait(delay, onLog)
                    if (cancelFlag.get()) return
                }
                executeProgramAction(
                    act, xMin, yMin, xMax, yMax, rnd,
                    cs, onLog, g, idx + 1, program.actions.size
                )
            }

            // 组间延迟（最后一组不等）
            if (g < program.groups && program.delayBetweenActions) {
                val delay = rnd.nextLong(program.delayMinMs, program.delayMaxMs + 1)
                onLog("")
                logWait(delay, onLog, label = "组间等待")
                onLog("")
            }
        }
    }

    /**
     * 执行单个程序化动作：按动作类型在坐标范围内随机生成起止点，调 [doSwipe]
     *
     * 坐标语义（竖屏 VD）：
     *   - SWIPE_UP_*：直线上滑（页面下移），起点下半区、终点上半区
     *   - ARC_SWIPE_LEFT_UP：右下→左上，控制点偏左（左凸弧）
     *   - ARC_SWIPE_RIGHT_UP：左下→右上，控制点偏右（右凸弧）
     */
    private fun executeProgramAction(
        act: ProgramAction,
        xMin: Int, yMin: Int, xMax: Int, yMax: Int,
        rnd: kotlin.random.Random,
        cs: CompositionService,
        onLog: (String) -> Unit,
        group: Int, idx: Int, total: Int
    ) {
        val midX = (xMin + xMax) / 2
        val midY = (yMin + yMax) / 2
        val label = act.label.ifBlank { act.type.name }
        val tag = "[$idx/$total]"
        val startMs = System.currentTimeMillis()

        when (act.type) {
            ProgramActionType.SWIPE_UP_FAST,
            ProgramActionType.SWIPE_UP_SLOW -> {
                // 直线上滑：起点下半区，终点上半区，横向轻微抖动
                val startX = rnd.nextInt(xMin, xMax + 1)
                val startY = rnd.nextInt(midY, yMax + 1)
                val endX = (startX + rnd.nextInt(-80, 81)).coerceIn(xMin, xMax)
                val endY = rnd.nextInt(yMin, midY)
                onLog("$tag ↔  $label · ${act.durationMs}ms")
                onLog("     起点 ($startX, $startY) → 终点 ($endX, $endY)")
                doSwipe(startX, startY, endX, endY, act.durationMs, null, null, cs)
                onLog("     ✓ 完成 · 耗时 ${System.currentTimeMillis() - startMs}ms")
            }
            ProgramActionType.ARC_SWIPE_LEFT_UP -> {
                // 右下→左上，控制点偏左（左凸）
                val startX = rnd.nextInt(midX, xMax + 1)
                val startY = rnd.nextInt(midY, yMax + 1)
                val endX = rnd.nextInt(xMin, midX + 1)
                val endY = rnd.nextInt(yMin, midY + 1)
                val ctrlX = (startX + endX) / 2 - rnd.nextInt(80, 181)
                val ctrlY = (startY + endY) / 2
                onLog("$tag 🔄 $label · ${act.durationMs}ms")
                onLog("     起点 ($startX, $startY) → 终点 ($endX, $endY)")
                onLog("     控制点 ($ctrlX, $ctrlY) · 左凸弧")
                doSwipe(startX, startY, endX, endY, act.durationMs, ctrlX, ctrlY, cs)
                onLog("     ✓ 完成 · 耗时 ${System.currentTimeMillis() - startMs}ms")
            }
            ProgramActionType.ARC_SWIPE_RIGHT_UP -> {
                // 左下→右上，控制点偏右（右凸）
                val startX = rnd.nextInt(xMin, midX + 1)
                val startY = rnd.nextInt(midY, yMax + 1)
                val endX = rnd.nextInt(midX, xMax + 1)
                val endY = rnd.nextInt(yMin, midY + 1)
                val ctrlX = (startX + endX) / 2 + rnd.nextInt(80, 181)
                val ctrlY = (startY + endY) / 2
                onLog("$tag 🔄 $label · ${act.durationMs}ms")
                onLog("     起点 ($startX, $startY) → 终点 ($endX, $endY)")
                onLog("     控制点 ($ctrlX, $ctrlY) · 右凸弧")
                doSwipe(startX, startY, endX, endY, act.durationMs, ctrlX, ctrlY, cs)
                onLog("     ✓ 完成 · 耗时 ${System.currentTimeMillis() - startMs}ms")
            }
        }
    }

    // ===== 通用滑动 + 工具方法 =====

    /**
     * 通用滑动：支持直线（control 为 null）与二次贝塞尔圆弧
     *
     * 贝塞尔公式：P(t) = (1-t)²·P0 + 2(1-t)t·P1 + t²·P2
     *   P0 = 起点, P2 = 终点, P1 = 控制点（决定弧度方向）
     *
     * 坐标 clamp 到 [0, vdW]×[0, vdH]，避免注入越界坐标。
     */
    private fun doSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long,
        controlX: Int?, controlY: Int?,
        cs: CompositionService
    ) {
        val vdW = cs.width
        val vdH = cs.height
        val sx = startX.coerceIn(0, vdW)
        val sy = startY.coerceIn(0, vdH)
        val ex = endX.coerceIn(0, vdW)
        val ey = endY.coerceIn(0, vdH)
        cs.injectTouchDown(sx, sy)
        val dur = durationMs.coerceAtLeast(50L)
        val stepMs = dur / SWIPE_STEPS
        for (i in 1..SWIPE_STEPS) {
            if (cancelFlag.get()) {
                cs.injectTouchUp(ex, ey)
                return
            }
            val t = i.toFloat() / SWIPE_STEPS
            val cx: Int
            val cy: Int
            if (controlX != null && controlY != null) {
                // 二次贝塞尔曲线插值
                val mt = 1f - t
                cx = (mt * mt * sx + 2 * mt * t * controlX + t * t * ex).toInt()
                cy = (mt * mt * sy + 2 * mt * t * controlY + t * t * ey).toInt()
            } else {
                cx = lerp(sx, ex, t)
                cy = lerp(sy, ey, t)
            }
            cs.injectTouchMove(sx, sy, cx.coerceIn(0, vdW), cy.coerceIn(0, vdH))
            sleepInterruptible(stepMs.coerceAtLeast(8L))
        }
        cs.injectTouchUp(ex, ey)
    }

    /**
     * 打印等待日志（开始 + 结束 + 实际耗时）
     *
     * @param ms      等待时长
     * @param onLog   日志回调
     * @param label   等待类型标签（默认"等待"）
     */
    private fun logWait(
        ms: Long,
        onLog: (String) -> Unit,
        label: String = "等待"
    ) {
        val startMs = System.currentTimeMillis()
        onLog("⏳ $label ${ms}ms ...")
        sleepInterruptible(ms)
        onLog("     ✓ $label 结束 · 实际 ${System.currentTimeMillis() - startMs}ms")
    }

    /**
     * 生成任务总耗时日志行（秒，保留 1 位小数）
     *
     * @param startMs 任务启动时间戳（System.currentTimeMillis()）
     * @param label  结束原因标签（完成/停止/出错）
     * @return 日志字符串，形如 "⏱  总耗时：1分23秒（83.0s）"
     */
    private fun logTotalDuration(startMs: Long, label: String): String {
        val durMs = System.currentTimeMillis() - startMs
        val durSec = durMs / 1000.0
        // 大于 60 秒用"分秒"更友好，否则直接秒
        val friendly = if (durSec >= 60.0) {
            val m = (durSec / 60).toInt()
            val s = (durSec % 60).toInt()
            "${m}分${s}秒"
        } else {
            "${durSec.format(1)}秒"
        }
        return "⏱  总耗时：$friendly（${durSec.format(1)}s）· $label"
    }

    /** Double 格式化到指定小数位（避免 String.format 受 locale 影响） */
    private fun Double.format(digits: Int): String {
        var v = this
        val factor = Math.pow(10.0, digits.toDouble())
        v = Math.round(v * factor) / factor
        val s = v.toString()
        // 补足小数位（如 5 → 5.0）
        return if (digits > 0 && !s.contains('.')) "$s.${"0".repeat(digits)}"
               else s
    }

    /** 可被取消的 sleep：检查 cancelFlag 后才睡眠，被取消时立即返回 */
    private fun sleepInterruptible(ms: Long) {
        if (ms <= 0) return
        val step = 50L
        var remaining = ms
        while (remaining > 0 && !cancelFlag.get()) {
            val sleep = remaining.coerceAtMost(step)
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining -= sleep
        }
    }

    /** 线性插值（Int → Float，避免精度丢失） */
    private fun lerp(start: Int, end: Int, t: Float): Int {
        return (start + (end - start) * t).toInt()
    }
}
