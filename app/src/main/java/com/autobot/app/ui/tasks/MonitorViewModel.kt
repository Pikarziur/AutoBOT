package com.autobot.app.ui.tasks

import android.app.Application
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobot.app.AutoBOTApp
import com.autobot.app.manager.AppManager
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.TaskExecutor
import com.autobot.app.manager.TaskFileManager
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskFile
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.service.CompositionService
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
 * ★重要变更（替代 SH_ADB 模式）★：
 *   - 不再持有 SH 脚本任务列表 / SH-ADB 模式枚举 / executeShAdbTask
 *   - 改为持有 [TaskFile] 列表（来自 filesDir/tasks/）+ selectedTaskFileId
 *   - executeTask 通过 [TaskExecutor] 在 App 进程内直接驱动 MotionEvent 注入到 VD
 *     （与 MAA-Meow 一致：IInputManager.injectInputEvent + setDisplayId，不走 adb shell）
 *   - CompositionService 来自 [AutoBOTApp.getCompositionService] app 级单例，
 *     Activity 销毁时 VD 不再被 stop，配合 TaskService 前台服务保活"切后台/小窗继续执行"
 *
 * 职责：
 * 1. 持有 CompositionService 单例 + 管理 VD 生命周期（启动/重启/停止）
 * 2. 收到 Surface 后通过 attachPreviewSurface 绑定到 Native 层
 * 3. 维护触摸标记列表供 TouchPreviewOverlay 显示
 * 4. 暴露 taskFiles/selectedTaskFileId 供 UI 下拉选择
 * 5. executeTask 调 TaskExecutor.submit，stopExecuting 调 TaskExecutor.stop
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MonitorViewModel"
        private const val LOG_MAX_LINES = 500
    }

    /** ★App 级单例 CompositionService：跨 Activity 生命周期保活 VD */
    private val compositionService = AutoBOTApp.getCompositionService()

    /** 预览 Surface 状态：null 表示未绑定 */
    private val _previewSurface = MutableStateFlow<Surface?>(null)
    val previewSurface: StateFlow<Surface?> = _previewSurface.asStateFlow()

    /** 虚拟显示器分辨率（默认按设置档位初始化） */
    private val _displaySize = MutableStateFlow(
        with(CompositionService.resolveMode(application)) { width to height }
    )
    val displaySize: StateFlow<Pair<Int, Int>> = _displaySize.asStateFlow()

    /** 虚拟显示器是否已启动 */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 虚拟显示器 Display ID */
    val displayId: Int get() = compositionService.displayId

    /** 已捕获帧数 */
    private val _frameCount = MutableStateFlow(0L)
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    /** 触摸标记列表（TouchPreviewOverlay 显示用） */
    data class TouchMarker(val x: Float, val y: Float, val timestamp: Long)
    private val _touchMarkers = MutableStateFlow<List<TouchMarker>>(emptyList())
    val touchMarkers: StateFlow<List<TouchMarker>> = _touchMarkers.asStateFlow()

    /** Shizuku 授权状态 */
    private val _shizukuGranted = MutableStateFlow(ShizukuManager.isShizukuGranted())
    val shizukuGranted: StateFlow<Boolean> = _shizukuGranted.asStateFlow()

    /** 全屏模式状态（提升到 ViewModel 层，供 Activity 观察、隐藏底部导航栏） */
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    fun setFullscreen(value: Boolean) {
        _isFullscreen.value = value
    }

    /** 当前内容方向 */
    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape.asStateFlow()

    /**
     * 当前 VD 中已启动 & 隔离到前台的目标 App 包名。
     *   - 例如："com.taobao.taobao"
     *   - 未启动目标 App 时为 null。
     */
    private val _vdTargetPackage = MutableStateFlow<String?>(null)
    val vdTargetPackage: StateFlow<String?> = _vdTargetPackage.asStateFlow()

    private val _vdTargetSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val vdTargetSize: StateFlow<Pair<Int, Int>?> = _vdTargetSize.asStateFlow()

    // ==================== 任务文件选择（替代 SH 脚本任务） ====================

    /** 任务文件列表（从 filesDir/tasks/ 加载，由 TaskFileManager 维护） */
    private val _taskFiles = MutableStateFlow<List<TaskFile>>(emptyList())
    val taskFiles: StateFlow<List<TaskFile>> = _taskFiles.asStateFlow()

    /** 当前选中的任务文件 id（null 表示未选中） */
    private val _selectedTaskFileId = MutableStateFlow<String?>(null)
    val selectedTaskFileId: StateFlow<String?> = _selectedTaskFileId.asStateFlow()

    /** 任务执行状态：true 表示正在执行（按钮变形 + 防抖） */
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    /** 最近一次任务执行的提示信息（供 UI Toast 显示） */
    private val _executeMessage = MutableStateFlow<String?>(null)
    val executeMessage: StateFlow<String?> = _executeMessage.asStateFlow()

    /**
     * 脚本执行日志流（UI 日志 Tab 专用，由 taskListener + TaskExecutor 回调写入）
     */
    private val _scriptLogs = MutableStateFlow<List<String>>(emptyList())
    val scriptLogs: StateFlow<List<String>> = _scriptLogs.asStateFlow()

    private val logLock = Any()
    private val logSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * TaskManager 事件监听器：将任务生命周期事件转为 UI 日志
     *
     * ★按钮状态同步★：
     *   - onTaskStarted → _isExecuting = true（按钮变红「■ 停止」）
     *   - onTaskCompleted / onTaskStopped / onTaskError → _isExecuting = false（按钮变回「▶ 执行任务」）
     *
     * 不使用 TaskExecutor.isExecuting() 查询，因为 notifyCompleted 在协程内部
     * 调用时 runningJob.isActive 仍为 true（finally 块尚未执行），存在时序竞态，
     * 会导致按钮卡在"停止"状态不变回。直接按事件语义置 false 最可靠。
     */
    private val taskListener = object : TaskManager.TaskListener {
        override fun onTaskStarted(taskId: String, taskName: String) {
            appendScriptLog("[${stamp()}] ⟶ 启动任务『$taskName』 (id=${taskId.take(4)})")
            _isExecuting.value = true
        }

        override fun onTaskOutput(taskId: String, line: String) {
            appendScriptLog("[${stamp()}] [${taskId.take(4)}] $line")
        }

        override fun onTaskCompleted(taskId: String) {
            appendScriptLog("[${stamp()}] ✓ 任务完成 (id=${taskId.take(4)})")
            _isExecuting.value = false
        }

        override fun onTaskStopped(taskId: String, reason: String) {
            appendScriptLog("[${stamp()}] ⏹ 任务被停止 (id=${taskId.take(4)}, reason=$reason)")
            _isExecuting.value = false
        }

        override fun onTaskError(taskId: String, error: String) {
            appendScriptLog("[${stamp()}] ✗ 任务出错 (id=${taskId.take(4)}): $error")
            _isExecuting.value = false
        }
    }

    private fun stamp(): String = logSdf.format(Date())

    /** 追加一行脚本执行日志到 [_scriptLogs]，最多保留 500 行，线程安全 */
    private fun appendScriptLog(line: String) {
        synchronized(logLock) {
            val list = _scriptLogs.value.toMutableList()
            list.add(line)
            while (list.size > LOG_MAX_LINES) {
                list.removeAt(0)
            }
            _scriptLogs.value = list
        }
    }

    /**
     * 追加"启动目标 App / VD 控制链路"的日志到【脚本执行日志】流（UI 日志 Tab 可见）
     */
    private fun appendLauncherLog(line: String) = appendScriptLog(line)

    /**
     * 供 UI 层（TaobaoLauncherRow）在捕获到启动崩溃 Exception 时写入日志
     */
    fun reportLauncherCrash(fullText: String) {
        val stamp = stamp()
        fullText.lineSequence().forEachIndexed { idx, ln ->
            appendScriptLog(if (idx == 0) "[$stamp] ✗ $ln" else "[$stamp]   $ln")
        }
    }

    /** 供 UI 层临时塞一条 Toast 短提示 */
    fun pushExecuteMessage(msg: String) {
        _executeMessage.value = msg
    }

    init {
        // 尝试加载 Native 库
        try {
            NativeCapturer.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library load failed", e)
        }
        // 加载任务文件列表（从 filesDir/tasks/）
        refreshTaskFiles()
        // 注册 TaskManager 监听器：实时将任务执行日志推给 UI
        TaskManager.addListener(taskListener)
        // 恢复日志缓冲（App 在后台被杀重启后，已运行的日志会丢失，这里至少显示就绪提示）
        val existingLogs = TaskManager.getLogs()
        if (existingLogs.isNotEmpty()) {
            _scriptLogs.value = existingLogs
        } else {
            appendScriptLog("[${stamp()}] 日志区就绪：选择任务后点击「执行任务」按钮，注入到 VD")
        }
    }

    /**
     * 清空 UI 脚本日志 + TaskManager 日志缓冲
     */
    fun clearLogs() {
        synchronized(logLock) {
            TaskManager.clearLogs()
            _scriptLogs.value = emptyList()
            appendScriptLog("[${stamp()}] 日志已清空")
        }
    }

    /**
     * 刷新 Shizuku 授权状态
     */
    fun refreshShizukuStatus(): Boolean {
        val ctx = getApplication<Application>().applicationContext
        val diag = ShizukuManager.diagnoseShizuku(ctx)
        val text = ShizukuManager.getDiagnosisText(ctx, diag)
        val granted = diag == ShizukuManager.ShizukuDiagnosis.OK
        _shizukuGranted.value = granted
        appendLauncherLog("[${stamp()}] Shizuku 诊断：$text (code=$diag)")
        Log.i(TAG, "refreshShizukuStatus: diag=$diag, granted=$granted")
        return granted
    }

    /**
     * 刷新任务文件列表（从 TaskFileManager 拉取最新数据）
     *
     * 用户外部编辑/新增任务文件后调用，或切回 Tasks 页面时调用。
     */
    fun refreshTaskFiles() {
        TaskFileManager.reload()
        _taskFiles.value = TaskFileManager.getAllTasks()
        // 当前选中的任务被删除时重置为 null
        val currentId = _selectedTaskFileId.value
        if (currentId != null && _taskFiles.value.none { it.id == currentId }) {
            _selectedTaskFileId.value = null
        }
    }

    /**
     * 选择某个任务文件
     */
    fun selectTaskFile(id: String?) {
        _selectedTaskFileId.value = id
    }

    /**
     * 消费执行消息（UI 显示后调用，清空避免重复显示）
     */
    fun consumeExecuteMessage() {
        _executeMessage.value = null
    }

    /**
     * 执行任务（替代旧版 executeShAdbTask）
     *
     * 流程：
     *   1. VD 必须已启动（displayId > 0），否则提示用户先点播放按钮
     *   2. 必须已选中任务文件
     *   3. Shizuku 已授权（VD 已起来时本就已校验过，这里再保险一次）
     *   4. 若 VD 未启动目标 App，先自动启动淘宝到 VD 前台
     *   5. 调 TaskExecutor.submit(taskFile, compositionService)：
     *      在 IO 协程内按 action 序列驱动 MotionEvent 注入到 VD
     *      → 通过 stdin pipe 下发到 server 进程
     *      → server 用 IInputManager.injectInputEvent + setDisplayId 注入
     */
    fun executeTask() {
        if (_isExecuting.value) {
            Log.w(TAG, "Task already executing")
            return
        }

        // VD 就绪检查
        val vdDisplayId = compositionService.displayId
        if (vdDisplayId <= 0) {
            _executeMessage.value = "请先点击播放按钮启动虚拟显示器"
            return
        }

        val taskId = _selectedTaskFileId.value
        if (taskId == null) {
            _executeMessage.value = "请先在下拉列表选择一个任务"
            return
        }
        val taskFile = TaskFileManager.getTask(taskId)
        if (taskFile == null) {
            _executeMessage.value = "任务不存在，可能已被删除"
            refreshTaskFiles()
            return
        }

        // Shizuku 二次校验
        _shizukuGranted.value = ShizukuManager.isShizukuGranted()
        if (!_shizukuGranted.value) {
            _executeMessage.value = "Shizuku 未授权，无法注入 MotionEvent"
            return
        }

        // 若 VD 未启动目标 App，先启动+隔离淘宝
        val targetPkg = _vdTargetPackage.value ?: AppManager.DEFAULT_PACKAGE_TAOBAO
        val needsRelaunch = _vdTargetPackage.value == null
        if (needsRelaunch) {
            _executeMessage.value = "自动启动目标 App (淘宝) 到虚拟显示器，请稍候..."
            appendLauncherLog("[${stamp()}] ⟶ 检测到 VD 前台无目标 App，自动启动到 display=$vdDisplayId")
        }

        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // -------- 目标 App 前置启动 & 隔离到 VD 前台 --------
                if (needsRelaunch) {
                    val (ok, msg) = launchAppWithOrientationAdaptation(
                        context = getApplication<android.app.Application>(),
                        packageName = targetPkg
                    )
                    if (!ok) {
                        _executeMessage.value = "目标 App 启动失败，任务中止：$msg"
                        appendLauncherLog("[${stamp()}] ✗ 任务中止：目标 App 未启动到 VD：$msg")
                        _isExecuting.value = false
                        return@launch
                    }
                }

                // -------- 提交任务给 TaskExecutor --------
                // TaskExecutor.submit 内部会：
                //   1) 调 TaskManager.notifyStarted → 前台服务起来 + UI 按钮变形
                //   2) 在 IO 协程内顺序执行 taskFile.actions
                //   3) 每个 action 调 compositionService.injectTouchDown/Move/Up / injectBack
                //   4) 通过 onLog 回调把执行日志推给 UI
                //   5) 结束时调 notifyCompleted/Stopped/Error
                TaskExecutor.submit(
                    taskFile = taskFile,
                    compositionService = compositionService,
                    scope = viewModelScope,
                    onLog = { line -> TaskManager.notifyOutput(taskFile.id, line) }
                )
                _executeMessage.value = "任务已启动：${taskFile.name}（映射到显示器 #$vdDisplayId）"
                Log.i(TAG, "TaskExecutor.submit: ${taskFile.name}, vd=$vdDisplayId, " +
                        "actions=${taskFile.actions.size}")
            } catch (e: Exception) {
                Log.e(TAG, "executeTask failed", e)
                _executeMessage.value = "任务启动失败：${e.message}"
                _isExecuting.value = false
            }
            // 成功路径不重置 _isExecuting：交给 taskListener 在 onTaskCompleted/Stopped/Error 时同步
        }
    }

    /**
     * 停止当前正在执行的任务（UI 红色「■ 停止」按钮点击）
     *
     * 直接调 TaskExecutor.stop，TaskExecutor 内部会取消协程 + 上报 notifyStopped。
     */
    fun stopExecuting() {
        if (!_isExecuting.value && !TaskExecutor.isExecuting()) {
            return
        }
        TaskExecutor.stop()
        // 立即触发一次 TaskManager.stopAllTasks 的语义（让前台服务感知"用户要停"）
        // 注意：TaskExecutor.stop 内部已 cancel 协程；TaskManager.notifyStopped 由 TaskExecutor 上报
        val cnt = TaskManager.stopAllTasks()
        appendLauncherLog("[${stamp()}] ⏹ 用户点击停止：已请求结束 $cnt 个任务")
        // 不在此处查询 _isExecuting：TaskExecutor.stop 的 cancelAndJoin 在独立协程中异步执行，
        // 此处查 isExecuting() 可能仍返回 true（竞态）。按钮状态交给 listener.onTaskStopped 置 false。
        _executeMessage.value = if (cnt > 0) "已停止任务" else "当前没有运行中的任务"
    }

    // ==================== 虚拟显示器相关 ====================

    /**
     * 启动虚拟显示器
     */
    fun startVirtualDisplay(width: Int = -1, height: Int = -1) {
        if (_isRunning.value) {
            Log.i(TAG, "VirtualDisplay already running")
            return
        }

        _shizukuGranted.value = ShizukuManager.isShizukuGranted()
        if (!_shizukuGranted.value) {
            Log.w(TAG, "Shizuku not granted, abort startVirtualDisplay")
            return
        }

        val mode = CompositionService.resolveMode(getApplication())
        val realW = if (width > 0) width else mode.width
        val realH = if (height > 0) height else mode.height

        viewModelScope.launch(Dispatchers.IO) {
            val (surface, errMsg) =
                if (width <= 0 && height <= 0) compositionService.startVirtualDisplay()
                else compositionService.startVirtualDisplay(realW, realH)
            if (errMsg.isBlank()) {
                _displaySize.value = realW to realH
                _isLandscape.value = compositionService.isLandscape
                _isRunning.value = true
                Log.i(TAG, "VirtualDisplay launched: ${realW}x${realH} " +
                        "(mode=${compositionService.vdMode.name}, " +
                        "density=${compositionService.densityDpi}, " +
                        "landscape=${_isLandscape.value})")
            } else {
                val msg = errMsg.ifBlank { "VirtualDisplay launch failed" }
                Log.e(TAG, msg)
                appendLauncherLog("[${stamp()}] ✗ $msg")
                _executeMessage.value = msg
            }
        }
    }

    /**
     * 手动切换虚拟显示器横竖屏方向
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
            if (errMsg.isBlank()) {
                _displaySize.value = newW to newH
                _isLandscape.value = compositionService.isLandscape
                Log.i(TAG, "VirtualDisplay orientation toggled: ${newW}x${newH} (landscape=${_isLandscape.value})")
            } else {
                _isRunning.value = false
                val msg = errMsg.ifBlank { "虚拟显示器切换方向失败，请重试" }
                Log.e(TAG, "toggleOrientation: restartVirtualDisplay failed: $msg")
                appendLauncherLog("[${stamp()}] ✗ $msg")
                _executeMessage.value = msg
            }
        }
    }

    /**
     * 检测 App 首选方向并启动到虚拟显示器（自动适配横竖屏）
     */
    suspend fun launchAppWithOrientationAdaptation(
        context: Context,
        packageName: String
    ): Pair<Boolean, String> {
        if (packageName.isBlank()) {
            val r = false to "未选中有效应用"
            appendLauncherLog("[${stamp()}] ✗ ${r.second}, pkg=$packageName")
            _executeMessage.value = r.second; return r
        }
        val granted = refreshShizukuStatus()
        if (!granted) {
            val diag = ShizukuManager.diagnoseShizuku(context)
            val detail = ShizukuManager.getDiagnosisText(context, diag)
            val longMsg = "Shizuku 未就绪，无法启动虚拟显示器与 App：\n  $detail"
            val shortMsg = "Shizuku 未就绪：$detail"
            appendLauncherLog("[${stamp()}] ✗ ${longMsg.replace("\n", "  ")}")
            val r = false to shortMsg
            _executeMessage.value = r.second; return r
        }

        val pref = AppManager.getAppPreferredOrientation(context, packageName)
        Log.i(TAG, "launchAppWithOrientationAdaptation: pkg=$packageName orientation=$pref")

        val mode = CompositionService.resolveMode(context)
        val (baseW, baseH) = mode.width to mode.height
        val targetW: Int
        val targetH: Int
        when (pref) {
            AppManager.AppOrientation.LANDSCAPE -> {
                targetW = baseH
                targetH = baseW
            }
            else -> {
                targetW = baseW
                targetH = baseH
            }
        }

        if (_isRunning.value) {
            Log.i(TAG, "Force restart VD: stopping existing display before re-launch")
            compositionService.stopVirtualDisplay()
            _isRunning.value = false
            _vdTargetPackage.value = null
            _vdTargetSize.value = null
        }
        appendLauncherLog("[${stamp()}] ⟶ 启动虚拟显示器 ${targetW}x$targetH " +
                "(mode=${mode.name}) 并启动 pkg=$packageName")
        val (surface, errMsg) = compositionService.startVirtualDisplay(targetW, targetH, mode.dpi)
        if (errMsg.isBlank()) {
            _displaySize.value = targetW to targetH
            _isLandscape.value = compositionService.isLandscape
            _isRunning.value = true
            Log.i(TAG, "VD freshly started at ${targetW}x$targetH " +
                    "(mode=${compositionService.vdMode.name}, " +
                    "density=${compositionService.densityDpi}) for app $packageName")
        } else {
            val msg = errMsg.ifBlank { "虚拟显示器启动失败，请确认 Shizuku 已授权" }
            appendLauncherLog("[${stamp()}] ✗ $msg")
            val r = false to msg
            _executeMessage.value = r.second; return r
        }

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
            appendLauncherLog("[${stamp()}] ✗ ${r.second}，waited=${waited * 100}ms")
            _executeMessage.value = r.second; return r
        }

        val ok = AppManager.launchApp(context, packageName, dId)
        val launchResult = if (ok) {
            appendLauncherLog("[${stamp()}] ⟶ am start --display=$dId 已下发，等待 task 调度稳定后隔离回 VD...")
            val (moved, detail) = com.autobot.app.third.TaskIsolator.isolateToVirtualDisplay(
                packageName = packageName,
                vdDisplayId = dId,
                waitSettle = 1500L,
                useShizuku = true
            )
            val msg = "已启动到虚拟显示器 (${targetW}x${targetH})" +
                    if (moved) "，任务隔离完成" else "，任务隔离跳过"
            val success = true to msg
            _executeMessage.value = success.second
            appendLauncherLog("[${stamp()}] ✓ 已启动 pkg=$packageName 到 display=$dId（任务隔离结果：$detail）")
            success
        } else {
            val r = false to "App 启动命令失败，请确认目标应用已安装（$packageName）"
            _executeMessage.value = r.second
            appendLauncherLog("[${stamp()}] ✗ ${r.second}")
            r
        }
        if (launchResult.first) {
            _vdTargetPackage.value = packageName
            _vdTargetSize.value = targetW to targetH
        }
        return launchResult
    }

    /**
     * SurfaceView 创建/变化时绑定 Surface
     */
    fun onPreviewSurfaceReady(surface: Surface) {
        if (_previewSurface.value === surface) return
        _previewSurface.value = surface
        compositionService.attachPreviewSurface(surface)
        Log.i(TAG, "Preview surface attached: $surface")
    }

    fun onPreviewSurfaceDestroyed() {
        compositionService.detachPreviewSurface()
        _previewSurface.value = null
        Log.i(TAG, "Preview surface destroyed")
    }

    /**
     * 触摸事件 - 按下
     * 发送 MSG_TOUCH_DOWN 到 server 进程，server 用 IInputManager.injectInputEvent() 注入 MotionEvent
     */
    fun onTouchDown(vx: Int, vy: Int) {
        addTouchMarker(vx.toFloat(), vy.toFloat())
        compositionService.injectTouchDown(vx, vy)
    }

    fun onTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        compositionService.injectTouchMove(fromX, fromY, toX, toY)
    }

    fun onTouchUp(vx: Int, vy: Int) {
        compositionService.injectTouchUp(vx, vy)
    }

    /**
     * 返回键 - 注入 KEYCODE_BACK 到虚拟显示器
     */
    fun onBackPress() {
        compositionService.injectBack()
    }

    fun refreshFrameCount() {
        _frameCount.value = compositionService.getFrameCount()
    }

    private fun addTouchMarker(x: Float, y: Float) {
        val marker = TouchMarker(x, y, System.currentTimeMillis())
        val current = _touchMarkers.value.toMutableList()
        current.add(marker)
        if (current.size > 5) current.removeAt(0)
        _touchMarkers.value = current
    }

    fun clearTouchMarkers() {
        _touchMarkers.value = emptyList()
    }

    override fun onCleared() {
        TaskManager.removeListener(taskListener)
        // ★关键变更：不再 stopVirtualDisplay
        // VD 是 app 级单例，跨 Activity 生命周期保活；切到后台/小窗仍可继续执行任务。
        // 真正释放时机：用户主动停止 VD，或 App 进程被系统杀掉时随进程清理
        // （server 进程 stdin pipe EOF 后会自动 exit）。
        _previewSurface.value = null
        super.onCleared()
    }
}
