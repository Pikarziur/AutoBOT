package com.autobot.app.ui.tasks

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobot.app.manager.AppManager
import com.autobot.app.manager.ScriptTaskManager
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskStatus
import com.autobot.app.model.TaskType
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.service.CompositionService
import com.autobot.app.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 虚拟显示器预览 ViewModel
 *
 * 职责：
 * 1. 持有 CompositionService 并管理虚拟显示器生命周期
 * 2. 收到 Surface 后通过 attachPreviewSurface 绑定到 Native 层
 * 3. Surface 销毁时调用 detachPreviewSurface
 * 4. 提供 onTouchDown/onTouchMove/onTouchUp 注入触摸事件
 * 5. 维护触摸标记列表供 TouchPreviewOverlay 显示
 * 6. 管理任务执行模式选择（SH-ADB / 截图识别）和 SH 脚本任务列表
 *
 * 虚拟显示器创建路径：
 *   CompositionService → DisplayManagerHelper → ShizukuBinderWrapper →
 *   DisplayManager.createVirtualDisplay（shell uid 持有 MANAGE_DISPLAYS 权限）
 *   全程不弹窗、不需要用户运行时确认；前置条件仅为 Shizuku 已授权
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MonitorViewModel"
        private const val LOG_MAX_LINES = 500
    }

    private val compositionService = CompositionService(application)

    /** 预览 Surface 状态：null 表示未绑定 */
    private val _previewSurface = MutableStateFlow<Surface?>(null)
    val previewSurface: StateFlow<Surface?> = _previewSurface.asStateFlow()

    /** 虚拟显示器分辨率 */
    private val _displaySize = MutableStateFlow(
        CompositionService.DEFAULT_WIDTH to CompositionService.DEFAULT_HEIGHT
    )
    val displaySize: StateFlow<Pair<Int, Int>> = _displaySize.asStateFlow()

    /** 虚拟显示器是否已启动 */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * 虚拟显示器 Display ID（用于让 App 启动到虚拟显示器）
     * - 已启动：>0
     * - 未启动：-1
     */
    val displayId: Int get() = compositionService.displayId

    /** 已捕获帧数 */
    private val _frameCount = MutableStateFlow(0L)
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    /** 触摸标记列表（TouchPreviewOverlay 显示用） */
    data class TouchMarker(val x: Float, val y: Float, val timestamp: Long)
    private val _touchMarkers = MutableStateFlow<List<TouchMarker>>(emptyList())
    val touchMarkers: StateFlow<List<TouchMarker>> = _touchMarkers.asStateFlow()

    /** Shizuku 授权状态：未授权时 startVirtualDisplay 会直接失败 */
    private val _shizukuGranted = MutableStateFlow(ShizukuManager.isShizukuGranted())
    val shizukuGranted: StateFlow<Boolean> = _shizukuGranted.asStateFlow()

    /** 全屏模式状态（提升到 ViewModel 层，供 Activity 观察、隐藏底部导航栏） */
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    fun setFullscreen(value: Boolean) {
        _isFullscreen.value = value
    }

    /**
     * 当前内容方向（用于 UI 全屏模式锁定 Activity 方向）
     *   true  = 横屏（如 960x540）
     *   false = 竖屏（如 540x960）
     *  默认跟随 CompositionService.orientation；用户手动切换后会覆盖
     */
    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape.asStateFlow()

    // ==================== 模式选择 / SH 脚本任务 ====================

    /** 任务执行模式枚举 */
    enum class TaskMode {
        SH_ADB,           // SH-ADB 模式：通过 SH 文件执行 ADB 指令
        SCREENSHOT_RECOGNITION  // 截图识别模式（占位，暂未实现）
    }

    /** 当前选中的模式（默认 SH-ADB） */
    private val _selectedMode = MutableStateFlow(TaskMode.SH_ADB)
    val selectedMode: StateFlow<TaskMode> = _selectedMode.asStateFlow()

    /** SH 脚本任务列表（从 ScriptTaskManager 加载，受 selectedMode 影响 UI 启用状态） */
    private val _scriptTasks = MutableStateFlow<List<ScriptTaskManager.ScriptTask>>(emptyList())
    val scriptTasks: StateFlow<List<ScriptTaskManager.ScriptTask>> = _scriptTasks.asStateFlow()

    /** 当前选中的 SH 脚本任务 id（null 表示未选中） */
    private val _selectedScriptTaskId = MutableStateFlow<String?>(null)
    val selectedScriptTaskId: StateFlow<String?> = _selectedScriptTaskId.asStateFlow()

    /** 任务执行状态：true 表示正在执行（按钮防抖） */
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    /** 最近一次任务执行的提示信息（供 UI Toast/Snackbar 显示） */
    private val _executeMessage = MutableStateFlow<String?>(null)
    val executeMessage: StateFlow<String?> = _executeMessage.asStateFlow()

    /**
     * 任务日志流（UI 实时显示用）
     * - 每行格式：[HH:mm:ss] [任务名/ID] [OUT]/[ERR]/[START]/[DONE] ... 内容
     * - 用 List<String> 以便 Compose LazyColumn 逐条渲染并自动滚动到底
     * - 保留最多 500 行，避免内存无限增长
     */
    private val _taskLogs = MutableStateFlow<List<String>>(emptyList())
    val taskLogs: StateFlow<List<String>> = _taskLogs.asStateFlow()

    private val logLock = Any()
    private val logSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * TaskManager 事件监听器：将任务生命周期和输出转为 UI 日志
     */
    private val taskListener = object : TaskManager.TaskListener {
        override fun onTaskStarted(task: com.autobot.app.model.TaskInfo) {
            appendLog("[${stamp()}] ⟶ 启动任务『${task.name}』 (id=${task.id.substring(0, 4)} type=${task.type})")
        }
        override fun onTaskOutput(task: com.autobot.app.model.TaskInfo, line: String) {
            appendLog("[${stamp()}] [${task.id.substring(0, 4)}] $line")
        }
        override fun onTaskCompleted(task: com.autobot.app.model.TaskInfo) {
            val duration = task.getDurationText()
            appendLog("[${stamp()}] ✓ 任务『${task.name}』完成 (exit=${task.exitCode}, duration=$duration)")
        }
        override fun onTaskStopped(task: com.autobot.app.model.TaskInfo) {
            appendLog("[${stamp()}] ⏹ 任务『${task.name}』被停止 (id=${task.id.substring(0, 4)})")
        }
        override fun onTaskError(task: com.autobot.app.model.TaskInfo, error: String) {
            appendLog("[${stamp()}] ✗ 任务『${task.name}』出错: $error")
        }
    }

    private fun stamp(): String = logSdf.format(Date())

    /** 追加一行日志（最多保留 500 行），线程安全 */
    private fun appendLog(line: String) {
        synchronized(logLock) {
            val list = _taskLogs.value.toMutableList()
            list.add(line)
            while (list.size > LOG_MAX_LINES) {
                list.removeAt(0)
            }
            _taskLogs.value = list
        }
    }

    init {
        // 尝试加载 Native 库
        try {
            NativeCapturer.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library load failed", e)
        }
        // 加载已持久化的 SH 脚本任务列表
        refreshScriptTasks()
        // 注册 TaskManager 监听器：实时将任务日志推给 UI
        TaskManager.addListener(taskListener)
        // 欢迎日志（避免空列表）
        appendLog("[${stamp()}] 日志区就绪：SH-ADB 任务执行后 stdout/stderr 将实时显示在此")
    }

    /**
     * 清空 UI 任务日志（仅清空显示，不影响 TaskManager 的历史）
     */
    fun clearLogs() {
        synchronized(logLock) {
            _taskLogs.value = emptyList()
            appendLog("[${stamp()}] 日志已清空")
        }
    }

    /**
     * 刷新 Shizuku 授权状态（从首页授权切回 TasksFragment 时必须调用）
     * 同时打印详细诊断日志到日志区，方便排查"已授权但仍失败"的情况
     *
     * @return 刷新后是否已授权
     */
    fun refreshShizukuStatus(): Boolean {
        val ctx = getApplication<Application>().applicationContext
        val diag = ShizukuManager.diagnoseShizuku(ctx)
        val text = ShizukuManager.getDiagnosisText(ctx, diag)
        val granted = diag == ShizukuManager.ShizukuDiagnosis.OK
        _shizukuGranted.value = granted

        // 同步写入日志区（用户肉眼可见，避免"为什么明明授权了还报错"的困惑）
        appendLog("[${stamp()}] Shizuku 诊断：$text (code=$diag)")
        Log.i(TAG, "refreshShizukuStatus: diag=$diag, granted=$granted")
        return granted
    }

    /**
     * 刷新 SH 脚本任务列表（从 ScriptTaskManager 拉取最新数据）
     */
    fun refreshScriptTasks() {
        _scriptTasks.value = ScriptTaskManager.getAllTasks()
        // 如果当前选中的任务已被删除，重置为 null
        val currentId = _selectedScriptTaskId.value
        if (currentId != null && _scriptTasks.value.none { it.id == currentId }) {
            _selectedScriptTaskId.value = null
        }
    }

    /**
     * 切换模式
     */
    fun selectMode(mode: TaskMode) {
        if (_selectedMode.value == mode) return
        _selectedMode.value = mode
        Log.i(TAG, "Mode switched to: $mode")
    }

    /**
     * 选择某个 SH 脚本任务
     */
    fun selectScriptTask(id: String?) {
        _selectedScriptTaskId.value = id
    }

    /**
     * 导入 SH 脚本文件（从 SAF Uri 读取并存储到 app 内部）
     * @param uri SAF 返回的文件 Uri
     * @return 成功返回 true
     */
    fun importScript(uri: Uri): Boolean {
        val task = ScriptTaskManager.importScript(uri) ?: run {
            _executeMessage.value = "导入脚本失败"
            return false
        }
        refreshScriptTasks()
        // 自动选中刚导入的任务
        _selectedScriptTaskId.value = task.id
        _executeMessage.value = "已导入：${task.name}"
        return true
    }

    /**
     * 删除指定的 SH 脚本任务
     */
    fun deleteScriptTask(id: String) {
        ScriptTaskManager.deleteTask(id)
        refreshScriptTasks()
        if (_selectedScriptTaskId.value == id) {
            _selectedScriptTaskId.value = null
        }
        _executeMessage.value = "已删除任务"
    }

    /**
     * 消费执行消息（UI 显示后调用，清空避免重复显示）
     */
    fun consumeExecuteMessage() {
        _executeMessage.value = null
    }

    /**
     * 执行任务（联动当前选中的模式）
     * - SH-ADB 模式：调用 TaskManager.submitTask 执行选中的 SH 脚本
     *   （通过 ShellExecutor.executeScript → Shizuku 执行 SH 中的 ADB 指令）
     * - 截图识别模式：占位提示"功能开发中"
     */
    fun executeTask() {
        if (_isExecuting.value) {
            Log.w(TAG, "Task already executing")
            return
        }

        when (_selectedMode.value) {
            TaskMode.SH_ADB -> executeShAdbTask()
            TaskMode.SCREENSHOT_RECOGNITION -> {
                _executeMessage.value = "截图识别模式开发中，敬请期待"
                Log.i(TAG, "Screenshot recognition mode not implemented yet")
            }
        }
    }

    /**
     * SH-ADB 模式：执行选中的 SH 脚本任务
     */
    private fun executeShAdbTask() {
        val taskId = _selectedScriptTaskId.value
        if (taskId == null) {
            _executeMessage.value = "请先选择一个 SH 脚本任务"
            return
        }
        val task = ScriptTaskManager.getTask(taskId)
        if (task == null) {
            _executeMessage.value = "任务不存在，可能已被删除"
            refreshScriptTasks()
            return
        }

        // 校验 Shizuku 已授权（SH 脚本通过 Shizuku 执行 ADB 指令）
        _shizukuGranted.value = ShizukuManager.isShizukuGranted()
        if (!_shizukuGranted.value) {
            _executeMessage.value = "Shizuku 未授权，无法执行 ADB 指令"
            return
        }

        // 校验脚本文件存在
        val scriptFile = java.io.File(task.scriptPath)
        if (!scriptFile.exists()) {
            _executeMessage.value = "脚本文件不存在：${task.scriptPath}"
            return
        }

        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 提交到 TaskManager 异步执行（type=SCRIPT 走 ShellExecutor.executeScript）
                TaskManager.submitTask(
                    name = task.name,
                    command = task.scriptPath,
                    type = TaskType.SCRIPT,
                    useShizuku = true
                )
                _executeMessage.value = "任务已启动：${task.name}"
                Log.i(TAG, "SH-ADB task submitted: ${task.name} -> ${task.scriptPath}")
            } catch (e: Exception) {
                Log.e(TAG, "executeShAdbTask failed", e)
                _executeMessage.value = "任务启动失败：${e.message}"
            } finally {
                _isExecuting.value = false
            }
        }
    }

    // ==================== 虚拟显示器相关 ====================

    /**
     * 启动虚拟显示器
     * 走 Shizuku 路径（不弹窗、不需要 MediaProjection 授权）：
     *   - Shizuku 未授权 → 直接失败并打日志（UI 应在调用前引导用户授权）
     *   - Shizuku 已授权 → CompositionService.startVirtualDisplay → DisplayServiceShizuku
     *
     * @param width  虚拟显示器宽度
     * @param height 虚拟显示器高度
     */
    fun startVirtualDisplay(width: Int = CompositionService.DEFAULT_WIDTH,
                            height: Int = CompositionService.DEFAULT_HEIGHT) {
        if (_isRunning.value) {
            Log.i(TAG, "VirtualDisplay already running")
            return
        }

        // 刷新 Shizuku 状态供 UI 感知
        _shizukuGranted.value = ShizukuManager.isShizukuGranted()
        if (!_shizukuGranted.value) {
            Log.w(TAG, "Shizuku not granted, abort startVirtualDisplay")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val (surface, errMsg) = compositionService.startVirtualDisplay(width, height)
            // 新架构下 VD 由 server 进程创建，App 端不再持有 VD Surface。
            // 用 errMsg 是否为空来判断成功/失败（而非 surface != null）
            if (errMsg.isBlank()) {
                _displaySize.value = width to height
                _isLandscape.value = compositionService.isLandscape
                _isRunning.value = true
                Log.i(TAG, "VirtualDisplay launched: ${width}x${height} (landscape=${_isLandscape.value})")
            } else {
                val msg = errMsg.ifBlank { "VirtualDisplay launch failed" }
                Log.e(TAG, msg)
                appendLog("[${stamp()}] ✗ $msg")
                _executeMessage.value = msg
            }
        }
    }

    /**
     * 手动切换虚拟显示器横竖屏方向（用户兜底按钮）
     *   - 交换宽高 → 调用 CompositionService.restartVirtualDisplay 重建 VD
     *   - 成功后更新 displaySize / isLandscape 供 UI 刷新
     */
    fun toggleDisplayOrientation() {
        if (!_isRunning.value) {
            Log.w(TAG, "toggleOrientation: VirtualDisplay not running")
            return
        }
        val (curW, curH) = _displaySize.value
        val newW = curH
        val newH = curW

        viewModelScope.launch(Dispatchers.IO) {
            val (newSurface, errMsg) = compositionService.restartVirtualDisplay(newW, newH)
            // 新架构下用 errMsg 判断成功/失败
            if (errMsg.isBlank()) {
                _displaySize.value = newW to newH
                _isLandscape.value = compositionService.isLandscape
                Log.i(TAG, "VirtualDisplay orientation toggled: ${newW}x${newH} (landscape=${_isLandscape.value})")
            } else {
                // 重启失败：VD 可能处于未启动状态，同步 UI 状态
                _isRunning.value = false
                val msg = errMsg.ifBlank { "虚拟显示器切换方向失败，请重试" }
                Log.e(TAG, "toggleOrientation: restartVirtualDisplay failed: $msg")
                appendLog("[${stamp()}] ✗ $msg")
                _executeMessage.value = msg
            }
        }
    }

    /**
     * 检测 App 首选方向并启动到虚拟显示器（自动适配横竖屏）
     *
     * 流程：
     *   1. 查询 App 的 Manifest 声明方向 (PORTRAIT / LANDSCAPE / UNSPECIFIED)
     *   2. 根据方向换算目标 VD 分辨率：
     *        PORTRAIT    → 540 x 960
     *        LANDSCAPE   → 960 x 540
     *        UNSPECIFIED → 保持当前 VD 不重建（默认竖屏兜底）
     *   3. 若目标分辨率与当前不一致 → 调用 restartVirtualDisplay 重建 VD
     *   4. 启动 App 到虚拟显示器 (am start --display <id>)
     *
     * @param context     Context，用于查询 PackageManager
     * @param packageName 目标 App 包名
     * @return  Pair<Boolean, String>：(是否成功, 提示信息/错误原因)
     */
    suspend fun launchAppWithOrientationAdaptation(
        context: Context,
        packageName: String
    ): Pair<Boolean, String> {
        if (packageName.isBlank()) {
            val r = false to "未选中有效应用"
            _executeMessage.value = r.second; return r
        }
        // Shizuku 校验：先刷新一次状态（解决授权后仍显示未授权的缓存问题）
        //  refreshShizukuStatus 内部会把诊断结果写入日志区，方便用户肉眼排查
        val granted = refreshShizukuStatus()
        if (!granted) {
            val diag = ShizukuManager.diagnoseShizuku(context)
            val detail = ShizukuManager.getDiagnosisText(context, diag)
            val r = false to "Shizuku 未就绪，无法启动虚拟显示器与 App：$detail"
            _executeMessage.value = r.second; return r
        }

        // 1. 检测 App 首选方向
        val pref = AppManager.getAppPreferredOrientation(context, packageName)
        Log.i(TAG, "launchAppWithOrientationAdaptation: pkg=$packageName orientation=$pref")

        // 2. 换算目标分辨率（竖屏基准 540x960，横屏则交换）
        val targetW: Int
        val targetH: Int
        when (pref) {
            AppManager.AppOrientation.LANDSCAPE -> {
                targetW = CompositionService.DEFAULT_HEIGHT  // 960
                targetH = CompositionService.DEFAULT_WIDTH   // 540
            }
            else -> {
                // PORTRAIT / UNSPECIFIED 都用竖屏（UNSPECIFIED 默认竖屏兜底）
                targetW = CompositionService.DEFAULT_WIDTH   // 540
                targetH = CompositionService.DEFAULT_HEIGHT  // 960
            }
        }

        // 3. 每次点击都强制重启虚拟显示器（先停后启），确保干净环境
        //    无论 VD 是否已运行、分辨率是否匹配，都先 stop 再 start
        if (_isRunning.value) {
            Log.i(TAG, "Force restart VD: stopping existing display before re-launch")
            compositionService.stopVirtualDisplay()
            _isRunning.value = false
        }
        val (surface, errMsg) = compositionService.startVirtualDisplay(targetW, targetH)
        if (errMsg.isBlank()) {
            _displaySize.value = targetW to targetH
            _isLandscape.value = compositionService.isLandscape
            _isRunning.value = true
            Log.i(TAG, "VD freshly started at ${targetW}x${targetH} for app $packageName")
        } else {
            val msg = errMsg.ifBlank { "虚拟显示器启动失败，请确认 Shizuku 已授权" }
            appendLog("[${stamp()}] ✗ $msg")
            val r = false to msg
            _executeMessage.value = r.second; return r
        }

        // 5. 等待 displayId 可用（重建后 displayId 会变化，最多等待 800ms）
        val maxWait = 8
        var waited = 0
        while (compositionService.displayId <= 0 && waited < maxWait) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {}
            waited++
        }

        val dId = compositionService.displayId
        if (dId <= 0) {
            val r = false to "虚拟显示器未就绪（displayId=-1），请稍后重试"
            _executeMessage.value = r.second; return r
        }

        // 6. 启动 App 到虚拟显示器
        val ok = AppManager.launchApp(context, packageName, dId)
        return if (ok) {
            val r = true to "已启动到虚拟显示器 (${targetW}x${targetH})"
            _executeMessage.value = r.second
            appendLog("[${stamp()}] ✓ ${r.second}, pkg=$packageName, display=$dId")
            r
        } else {
            val r = false to "App 启动命令失败，请确认目标应用已安装"
            _executeMessage.value = r.second
            appendLog("[${stamp()}] ✗ ${r.second}, pkg=$packageName")
            r
        }
    }

    /**
     * SurfaceView 创建/变化时绑定 Surface
     * 由 PreviewContent 的 surfaceChanged 回调触发
     */
    fun onPreviewSurfaceReady(surface: Surface) {
        if (_previewSurface.value === surface) return  // 防重复
        _previewSurface.value = surface
        compositionService.attachPreviewSurface(surface)
        Log.i(TAG, "Preview surface attached: $surface")
    }

    /**
     * SurfaceView 销毁时解绑并释放 Surface
     */
    fun onPreviewSurfaceDestroyed() {
        compositionService.detachPreviewSurface()
        _previewSurface.value = null
        Log.i(TAG, "Preview surface destroyed")
    }

    // ===== 触摸手势追踪（修复：每个手势只发一次 input 命令，避免快速 Move 导致进程爆炸崩溃）=====
    private var touchStartX = 0
    private var touchStartY = 0
    private var touchEndX = 0
    private var touchEndY = 0
    private var touchHasMoved = false

    /**
     * 触摸事件 - 按下
     * 仅记录起点，不执行任何 input 命令
     * @param vx 虚拟显示器坐标 X
     * @param vy 虚拟显示器坐标 Y
     */
    fun onTouchDown(vx: Int, vy: Int) {
        touchStartX = vx
        touchStartY = vy
        touchEndX = vx
        touchEndY = vy
        touchHasMoved = false
        addTouchMarker(vx.toFloat(), vy.toFloat())
    }

    /**
     * 触摸事件 - 移动
     * 仅更新终点坐标，不执行任何 input 命令（避免快速 Move 生成大量 Shizuku 进程导致崩溃）
     */
    fun onTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        touchEndX = toX
        touchEndY = toY
        if (toX != touchStartX || toY != touchStartY) {
            touchHasMoved = true
        }
    }

    /**
     * 触摸事件 - 抬起
     * 在手势结束时执行一次 input 命令（tap 或 swipe），在 IO 线程异步执行
     * 使用 --display <displayId> 将事件注入到虚拟显示器而非主屏幕
     */
    fun onTouchUp(vx: Int, vy: Int) {
        val startX = touchStartX
        val startY = touchStartY
        val endX = if (touchHasMoved) touchEndX else vx
        val endY = if (touchHasMoved) touchEndY else vy
        val moved = touchHasMoved
        val displayId = compositionService.displayId

        viewModelScope.launch(Dispatchers.IO) {
            if (displayId <= 0) {
                Log.w(TAG, "onTouchUp: displayId=$displayId, skip touch injection")
                return@launch
            }
            try {
                val cmd = if (moved) {
                    // 滑动手势：input --display <id> swipe <x1> <y1> <x2> <y2> <duration>
                    "input --display $displayId swipe $startX $startY $endX $endY 100"
                } else {
                    // 点击手势：input --display <id> tap <x> <y>
                    "input --display $displayId tap $startX $startY"
                }
                ShellExecutor.execute(cmd, useShizuku = true, timeout = 2000)
            } catch (e: Exception) {
                Log.e(TAG, "Touch injection failed: ${e.message}", e)
            }
        }
    }

    /**
     * 刷新帧计数（供 UI 定时轮询）
     */
    fun refreshFrameCount() {
        _frameCount.value = compositionService.getFrameCount()
    }

    private fun addTouchMarker(x: Float, y: Float) {
        val marker = TouchMarker(x, y, System.currentTimeMillis())
        val current = _touchMarkers.value.toMutableList()
        current.add(marker)
        // 只保留最近 5 个标记
        if (current.size > 5) current.removeAt(0)
        _touchMarkers.value = current
    }

    /**
     * 清空触摸标记
     */
    fun clearTouchMarkers() {
        _touchMarkers.value = emptyList()
    }

    override fun onCleared() {
        TaskManager.removeListener(taskListener)
        compositionService.stopVirtualDisplay()
        _isRunning.value = false
        _previewSurface.value = null
        super.onCleared()
    }
}
