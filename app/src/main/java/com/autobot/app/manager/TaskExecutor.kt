package com.autobot.app.manager

import com.autobot.app.model.ProgramAction
import com.autobot.app.model.ProgramActionType
import com.autobot.app.model.ProgramTask
import com.autobot.app.model.RecognitionTask
import com.autobot.app.model.RecognitionTaskMode
import com.autobot.app.model.TaskAction
import com.autobot.app.model.TaskActionType
import com.autobot.app.model.TaskFile
import com.autobot.app.recognition.RecognitionManager
import com.autobot.app.service.CompositionService
import com.autobot.app.util.BitmapPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object TaskExecutor {

    private const val TAG = "TaskExecutor"

    private const val SWIPE_STEPS = 16

    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    private var runningJob: Job? = null

    @Volatile
    private var currentTaskId: String = ""

    @Volatile
    private var currentTaskName: String = ""

    fun isExecuting(): Boolean = runningJob?.isActive == true

    fun currentTaskName(): String = currentTaskName

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
            val program = taskFile.program
            val recognition = taskFile.recognition
            val actionCount = when {
                program != null -> program.groups * program.actions.size
                recognition != null -> 1
                else -> taskFile.actions.size
            }
            val taskStartMs = System.currentTimeMillis()
            onLog("")
            onLog("╔══════════════════════════════════════════╗")
            onLog("║ 🚀 任务启动：${taskFile.name.padEnd(24).take(24)} ║")
            onLog("║ 📊 动作总数：${"$actionCount".padEnd(24).take(24)} ║")
            onLog("║ 📺 VD 尺寸：${"${compositionService.width}×${compositionService.height}".padEnd(24).take(24)} ║")
            onLog("║ 🆔 displayId：${"${compositionService.displayId}".padEnd(23).take(23)} ║")
            onLog("╚══════════════════════════════════════════╝")

            try {
                when {
                    recognition != null -> executeRecognition(recognition, compositionService, onLog)
                    program != null -> executeProgram(program, compositionService, onLog)
                    else -> executeActions(taskFile.actions, compositionService, onLog)
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
        val tag = "[${idx.toString().padStart(total.toString().length, '0')}/$total]"
        val startMs = System.currentTimeMillis()
        when (action.type) {
            TaskActionType.TAP -> {
                onLog("$tag 👆 点击    (${action.x}, ${action.y})")
                compositionService.injectTouchDown(action.x, action.y)
                sleepInterruptible(50L)
                compositionService.injectTouchUp(action.x, action.y)
                onLog("      └─ ✓ 完成 · ${System.currentTimeMillis() - startMs}ms")
            }
            TaskActionType.SWIPE -> {
                onLog("$tag ↔  滑动    (${action.x}, ${action.y}) → (${action.endX}, ${action.endY}) · ${action.durationMs}ms")
                doSwipe(action.x, action.y, action.endX, action.endY,
                        action.durationMs, null, null, compositionService)
                onLog("      └─ ✓ 完成 · ${System.currentTimeMillis() - startMs}ms")
            }
            TaskActionType.WAIT -> {
                onLog("$tag ⏳ 等待    ${action.ms}ms ...")
                sleepInterruptible(action.ms)
                onLog("      └─ ✓ 等待结束 · 实际 ${System.currentTimeMillis() - startMs}ms")
            }
            TaskActionType.BACK -> {
                onLog("$tag 🔙 返回键")
                compositionService.injectBack()
                sleepInterruptible(80L)
                onLog("      └─ ✓ 完成 · ${System.currentTimeMillis() - startMs}ms")
            }
        }
    }

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
        onLog("   ├─ 延迟范围：${program.delayMinMs}~${program.delayMaxMs}ms")
        onLog("   ├─ 坐标范围：($xMin,$yMin) ~ ($xMax,$yMax)")
        onLog("   └─ 组内打乱：${if (program.shuffleGroup) "是" else "否"}")
        onLog("")

        for (g in 1..program.groups) {
            if (cancelFlag.get()) return

            val groupActions = program.actions.toMutableList()
            if (program.shuffleGroup) groupActions.shuffle(rnd)

            onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            onLog("🔄 第 $g/${program.groups} 组")
            onLog("   └─ 顺序：${
                groupActions.joinToString(" → ") { it.label.ifBlank { it.type.name } }}")
            onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            for ((idx, act) in groupActions.withIndex()) {
                if (cancelFlag.get()) return
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

            if (g < program.groups && program.delayBetweenActions) {
                val delay = rnd.nextLong(program.delayMinMs, program.delayMaxMs + 1)
                onLog("")
                logWait(delay, onLog, label = "组间等待")
                onLog("")
            }
        }
    }

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
        val tag = "[${idx.toString().padStart(total.toString().length, '0')}/$total]"
        val startMs = System.currentTimeMillis()

        when (act.type) {
            ProgramActionType.SWIPE_UP_FAST,
            ProgramActionType.SWIPE_UP_SLOW -> {
                val startX = rnd.nextInt(xMin, xMax + 1)
                val startY = rnd.nextInt(midY, yMax + 1)
                val endX = (startX + rnd.nextInt(-80, 81)).coerceIn(xMin, xMax)
                val endY = rnd.nextInt(yMin, midY)
                onLog("$tag ↔  $label · ${act.durationMs}ms")
                onLog("      └─ ($startX, $startY) → ($endX, $endY)")
                doSwipe(startX, startY, endX, endY, act.durationMs, null, null, cs)
                onLog("      └─ ✓ 完成 · ${System.currentTimeMillis() - startMs}ms")
            }
            ProgramActionType.ARC_SWIPE_LEFT_UP -> {
                val startX = rnd.nextInt(midX, xMax + 1)
                val startY = rnd.nextInt(midY, yMax + 1)
                val endX = rnd.nextInt(xMin, midX + 1)
                val endY = rnd.nextInt(yMin, midY + 1)
                val ctrlX = (startX + endX) / 2 - rnd.nextInt(80, 181)
                val ctrlY = (startY + endY) / 2
                onLog("$tag 🔄 $label · ${act.durationMs}ms")
                onLog("      └─ ($startX, $startY) → ($endX, $endY) · 左凸弧")
                onLog("      └─ 控制点 ($ctrlX, $ctrlY)")
                doSwipe(startX, startY, endX, endY, act.durationMs, ctrlX, ctrlY, cs)
                onLog("      └─ ✓ 完成 · ${System.currentTimeMillis() - startMs}ms")
            }
            ProgramActionType.ARC_SWIPE_RIGHT_UP -> {
                val startX = rnd.nextInt(xMin, midX + 1)
                val startY = rnd.nextInt(midY, yMax + 1)
                val endX = rnd.nextInt(midX, xMax + 1)
                val endY = rnd.nextInt(yMin, midY + 1)
                val ctrlX = (startX + endX) / 2 + rnd.nextInt(80, 181)
                val ctrlY = (startY + endY) / 2
                onLog("$tag 🔄 $label · ${act.durationMs}ms")
                onLog("      └─ ($startX, $startY) → ($endX, $endY) · 右凸弧")
                onLog("      └─ 控制点 ($ctrlX, $ctrlY)")
                doSwipe(startX, startY, endX, endY, act.durationMs, ctrlX, ctrlY, cs)
                onLog("      └─ ✓ 完成 · ${System.currentTimeMillis() - startMs}ms")
            }
        }
    }

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

    private fun logWait(
        ms: Long,
        onLog: (String) -> Unit,
        label: String = "等待"
    ) {
        val startMs = System.currentTimeMillis()
        onLog("      ⏳ $label ${ms}ms ...")
        sleepInterruptible(ms)
        onLog("      └─ ✓ $label 结束 · 实际 ${System.currentTimeMillis() - startMs}ms")
    }

    private fun logTotalDuration(startMs: Long, label: String): String {
        val durMs = System.currentTimeMillis() - startMs
        val durSec = durMs / 1000.0
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
        return if (digits > 0 && !s.contains('.')) "$s.${"0".repeat(digits)}"
               else s
    }

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

    private fun lerp(start: Int, end: Int, t: Float): Int {
        return (start + (end - start) * t).toInt()
    }

    private suspend fun executeRecognition(
        task: RecognitionTask,
        cs: CompositionService,
        onLog: (String) -> Unit
    ) {
        onLog("")
        onLog("📋 识别配置：")
        onLog("   ├─ 模式：${task.mode}")
        onLog("   ├─ 目标文字：${if (task.targetText.isNotBlank()) "「${task.targetText}」" else "无"}")
        onLog("   ├─ 超时：${task.timeoutMs}ms")
        onLog("   ├─ 抓图间隔：${task.intervalMs}ms")
        onLog("   └─ 成功后延迟：${task.delayAfterSuccessMs}ms")
        onLog("")

        val startTime = System.currentTimeMillis()
        var attempts = 0
        val targetText = task.targetText

        while (!cancelFlag.get()) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= task.timeoutMs) {
                onLog("❌ 找不到目标文字[$targetText]（超时 ${task.timeoutMs}ms，尝试 $attempts 次）")
                return
            }

            attempts++
            val bitmap = cs.getFrameBufferBitmap()
            if (bitmap == null) {
                onLog("[${attempts.toString().padStart(3, '0')}] ⚠️  frameBuffer 为空，等待下一轮")
                delay(task.intervalMs)
                continue
            }

            try {
                onLog("[${attempts.toString().padStart(3, '0')}] 📸 抓图成功 ${bitmap.width}×${bitmap.height}")

                when (task.mode) {
                    RecognitionTaskMode.OCR, RecognitionTaskMode.BOTH -> {
                        val results = RecognitionManager.recognizeText(bitmap)
                        val match = results.find { it.text.contains(targetText) }
                        if (match != null) {
                            onLog("  └─ ✅ 已找到[$targetText]，并且已经完成点击")
                            onLog("     └─ 坐标：(${match.x}, ${match.y})")
                            onLog("     └─ 原文：${match.text}")
                            cs.injectTouchDown(match.x, match.y)
                            delay(50)
                            cs.injectTouchUp(match.x, match.y)
                            onLog("  └─ ✓ 点击完成，等待 ${task.delayAfterSuccessMs}ms 缓冲")
                            delay(task.delayAfterSuccessMs)
                            return
                        } else {
                            onLog("  └─ ❌ 找不到目标文字[$targetText]（识别到 ${results.size} 个文字块）")
                        }
                    }
                    RecognitionTaskMode.TEMPLATE -> {
                        onLog("  └─ ⚠️  TEMPLATE 模式暂未实现模板加载，跳过")
                    }
                }
            } finally {
                // CPU 优化：归池复用，避免每帧重新分配 8MB 堆外像素内存
                BitmapPool.release(bitmap)
            }

            delay(task.intervalMs)
        }
    }
}
