package com.autobot.app.manager

import android.util.Log
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
 * 协作关系：
 *   - submit(taskFile, compositionService, onLog, onDone)：
 *     启动一个 IO 协程执行动作序列；外部调用 [stop] 取消（coroutine.cancelAndJoin + 提前返回）
 *   - 所有动作执行均通过 [TaskManager] 报告生命周期（让前台服务 TaskService 感知）
 *
 * 与 [TaskManager] 的对接：
 *   - 启动时调 TaskManager.notifyStarted(...) 让前台服务起来 + UI 按钮变红
 *   - 输出每行日志到 TaskManager.notifyOutput(...) 让 UI 实时显示
 *   - 完成/停止/出错调对应 notify 方法
 */
object TaskExecutor {

    private const val TAG = "TaskExecutor"

    /**
     * swipe 动作插值步数：足够平滑，又不会高频写 pipe 拖慢 server
     *
     * 500ms 的 swipe 分 16 步 ≈ 30fps，与 VD 默认帧率一致
     */
    private const val SWIPE_STEPS = 16

    /** 当前正在执行的任务取消标志 */
    private val cancelFlag = AtomicBoolean(false)

    /** 当前任务协程 */
    @Volatile
    private var runningJob: Job? = null

    /** 当前正在执行的任务 id（用于日志/通知） */
    @Volatile
    private var currentTaskId: String = ""

    /** 当前正在执行的任务名（用于通知栏文案） */
    @Volatile
    private var currentTaskName: String = ""

    /** 是否正在执行（供 UI 按钮变形查询） */
    fun isExecuting(): Boolean = runningJob?.isActive == true

    /** 当前任务名（供通知栏文案） */
    fun currentTaskName(): String = currentTaskName

    /**
     * 提交并执行任务
     *
     * @param taskFile         要执行的任务文件
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
            onLog("[WARN] 已有任务正在执行，请先停止")
            return currentTaskId
        }

        currentTaskId = taskFile.id
        currentTaskName = taskFile.name
        cancelFlag.set(false)

        // 通知 TaskManager：任务开始（→ 前台服务起来 + UI 按钮变形）
        TaskManager.notifyStarted(taskFile.id, taskFile.name)

        runningJob = scope.launch(Dispatchers.IO) {
            onLog("[INFO] ⟶ 启动任务『${taskFile.name}』 (id=${taskFile.id}, actions=${taskFile.actions.size})")
            val displayId = compositionService.displayId
            onLog("[INFO] VD displayId=$displayId, size=${compositionService.width}×${compositionService.height}")
            try {
                executeActions(taskFile.actions, compositionService, onLog)
                if (cancelFlag.get()) {
                    onLog("[INFO] ⏹ 任务『${taskFile.name}』被用户停止")
                    TaskManager.notifyStopped(taskFile.id, "用户停止")
                } else {
                    onLog("[INFO] ✓ 任务『${taskFile.name}』完成")
                    TaskManager.notifyCompleted(taskFile.id)
                }
            } catch (e: Exception) {
                if (cancelFlag.get()) {
                    onLog("[INFO] ⏹ 任务『${taskFile.name}』被用户停止")
                    TaskManager.notifyStopped(taskFile.id, "用户停止")
                } else {
                    onLog("[ERROR] ✗ 任务『${taskFile.name}』出错: ${e.javaClass.simpleName}: ${e.message}")
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
            // 用一个独立协程跑 cancelAndJoin，避免调用方阻塞
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    job.cancelAndJoin()
                } catch (_: Exception) {
                    // 取消时忽略
                }
            }
        }
    }

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
        for ((index, action) in actions.withIndex()) {
            if (cancelFlag.get()) return
            executeAction(index, action, compositionService, onLog)
        }
    }

    private fun executeAction(
        index: Int,
        action: TaskAction,
        compositionService: CompositionService,
        onLog: (String) -> Unit
    ) {
        when (action.type) {
            TaskActionType.TAP -> {
                onLog("[ACT] #$index tap (${action.x}, ${action.y})")
                compositionService.injectTouchDown(action.x, action.y)
                sleepInterruptible(50L)  // 按住 50ms 模拟真人
                compositionService.injectTouchUp(action.x, action.y)
            }
            TaskActionType.SWIPE -> {
                onLog("[ACT] #$index swipe (${action.x}, ${action.y}) → (${action.endX}, ${action.endY}) dur=${action.durationMs}ms")
                compositionService.injectTouchDown(action.x, action.y)
                val dur = action.durationMs.coerceAtLeast(50L)
                val stepMs = dur / SWIPE_STEPS
                for (i in 1..SWIPE_STEPS) {
                    if (cancelFlag.get()) {
                        compositionService.injectTouchUp(action.endX, action.endY)
                        return
                    }
                    val t = i.toFloat() / SWIPE_STEPS
                    val cx = lerp(action.x, action.endX, t)
                    val cy = lerp(action.y, action.endY, t)
                    compositionService.injectTouchMove(action.x, action.y, cx, cy)
                    sleepInterruptible(stepMs.coerceAtLeast(8L))
                }
                compositionService.injectTouchUp(action.endX, action.endY)
            }
            TaskActionType.WAIT -> {
                onLog("[ACT] #$index wait ${action.ms}ms")
                sleepInterruptible(action.ms)
            }
            TaskActionType.BACK -> {
                onLog("[ACT] #$index back")
                compositionService.injectBack()
                sleepInterruptible(80L)  // 按键事件之间留一点间隔
            }
        }
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
