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
import com.autobot.app.recognition.RecognitionExecutor
import com.autobot.app.recognition.RecognitionMode
import com.autobot.app.service.CompositionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MonitorViewModel"
        private const val LOG_MAX_LINES = 500
    }

    /** ★App 级单例 CompositionService：跨 Activity 生命周期保活 VD */
    private val compositionService = AutoBOTApp.getCompositionService()

    private val _previewSurface = MutableStateFlow<Surface?>(null)
    val previewSurface: StateFlow<Surface?> = _previewSurface.asStateFlow()

    private val _displaySize = MutableStateFlow(
        with(CompositionService.resolveMode(application)) { width to height }
    )
    val displaySize: StateFlow<Pair<Int, Int>> = _displaySize.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val displayId: Int get() = compositionService.displayId

    private val _frameCount = MutableStateFlow(0L)
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    data class TouchMarker(val x: Float, val y: Float, val timestamp: Long)
    private val _touchMarkers = MutableStateFlow<List<TouchMarker>>(emptyList())
    val touchMarkers: StateFlow<List<TouchMarker>> = _touchMarkers.asStateFlow()

    private val _shizukuGranted = MutableStateFlow(ShizukuManager.isShizukuGranted())
    val shizukuGranted: StateFlow<Boolean> = _shizukuGranted.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    fun setFullscreen(value: Boolean) {
        _isFullscreen.value = value
    }

    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape.asStateFlow()

    private val _vdTargetPackage = MutableStateFlow<String?>(null)
    val vdTargetPackage: StateFlow<String?> = _vdTargetPackage.asStateFlow()

    private val _vdTargetSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val vdTargetSize: StateFlow<Pair<Int, Int>?> = _vdTargetSize.asStateFlow()

    private val _taskFiles = MutableStateFlow<List<TaskFile>>(emptyList())
    val taskFiles: StateFlow<List<TaskFile>> = _taskFiles.asStateFlow()

    private val _selectedTaskFileId = MutableStateFlow<String?>(null)
    val selectedTaskFileId: StateFlow<String?> = _selectedTaskFileId.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    // _executeMessage 保留为 VM 内部使用（UI 已迁移到 snackbar）
    private val _executeMessage = MutableStateFlow<String?>(null)
    val executeMessage: StateFlow<String?> = _executeMessage.asStateFlow()

    private val _snackbarMessage = Channel<String>(capacity = Channel.BUFFERED)
    val snackbarMessage: Flow<String> = _snackbarMessage.receiveAsFlow()

    // 统一的 toast/snackbar 消息发送入口（替代所有 _executeMessage.value = msg）
    fun showSnack(msg: String) {
        _snackbarMessage.trySend(msg)
    }

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

    private fun appendLauncherLog(line: String) = appendScriptLog(line)

    fun reportLauncherCrash(fullText: String) {
        val stamp = stamp()
        fullText.lineSequence().forEachIndexed { idx, ln ->
            appendScriptLog(if (idx == 0) "[$stamp] ✗ $ln" else "[$stamp]   $ln")
        }
    }

    fun pushExecuteMessage(msg: String) {
        showSnack(msg)
    }

    init {
        try {
            NativeCapturer.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library load failed", e)
        }
        refreshTaskFiles()
        TaskManager.addListener(taskListener)
        val existingLogs = TaskManager.getLogs()
        if (existingLogs.isNotEmpty()) {
            _scriptLogs.value = existingLogs
        } else {
            appendScriptLog("[${stamp()}] 日志区就绪：选择任务后点击「执行任务」按钮，注入到 VD")
        }
    }

    fun clearLogs() {
        synchronized(logLock) {
            TaskManager.clearLogs()
            _scriptLogs.value = emptyList()
            appendScriptLog("[${stamp()}] 日志已清空")
        }
    }

    fun refreshShizukuStatus(): Boolean {
        val ctx = getApplication<Application>().applicationContext
        val diag = ShizukuManager.diagnoseShizuku(ctx)
        val granted = diag == ShizukuManager.ShizukuDiagnosis.OK
        _shizukuGranted.value = granted
        Log.i(TAG, "refreshShizukuStatus: diag=$diag, granted=$granted")
        return granted
    }

    fun refreshTaskFiles() {
        TaskFileManager.reload()
        _taskFiles.value = TaskFileManager.getAllTasks()
        val currentId = _selectedTaskFileId.value
        if (currentId != null && _taskFiles.value.none { it.id == currentId }) {
            _selectedTaskFileId.value = null
        }
    }

    fun selectTaskFile(id: String?) {
        _selectedTaskFileId.value = id
    }

    fun consumeExecuteMessage() {
        _executeMessage.value = null  // 清空旧 StateFlow（保留兼容）
    }

    /** */
    fun executeTask() {
        if (_isExecuting.value) {
            Log.w(TAG, "Task already executing")
            return
        }

        val vdDisplayId = compositionService.displayId
        if (vdDisplayId <= 0) {
            showSnack("请先点击播放按钮启动虚拟显示器")
            return
        }

        val taskId = _selectedTaskFileId.value
        if (taskId == null) {
            showSnack("请先在下拉列表选择一个任务")
            return
        }
        val taskFile = TaskFileManager.getTask(taskId)
        if (taskFile == null) {
            showSnack("任务不存在，可能已被删除")
            refreshTaskFiles()
            return
        }

        _shizukuGranted.value = ShizukuManager.isShizukuGranted()
        if (!_shizukuGranted.value) {
            showSnack("Shizuku 未授权，无法注入 MotionEvent")
            return
        }

        val targetPkg = _vdTargetPackage.value ?: AppManager.DEFAULT_PACKAGE_TAOBAO
        val needsRelaunch = _vdTargetPackage.value == null
        if (needsRelaunch) {
            appendLauncherLog("[${stamp()}] ⟶ 检测到 VD 前台无目标 App，自动启动到 display=$vdDisplayId")
        }

        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (needsRelaunch) {
                    val (ok, msg) = launchAppWithOrientationAdaptation(
                        context = getApplication<android.app.Application>(),
                        packageName = targetPkg
                    )
                    if (!ok) {
                        showSnack("目标 App 启动失败，任务中止：$msg")
                        appendLauncherLog("[${stamp()}] ✗ 任务中止：目标 App 未启动到 VD：$msg")
                        _isExecuting.value = false
                        return@launch
                    }
                }

                TaskExecutor.submit(
                    taskFile = taskFile,
                    compositionService = compositionService,
                    scope = viewModelScope,
                    onLog = { line -> TaskManager.notifyOutput(taskFile.id, line) }
                )
                Log.i(TAG, "TaskExecutor.submit: ${taskFile.name}, vd=$vdDisplayId, " +
                        "actions=${taskFile.actions.size}")
            } catch (e: Exception) {
                Log.e(TAG, "executeTask failed", e)
                showSnack("任务启动失败：${e.message}")
                _isExecuting.value = false
            }
            // 成功路径不重置 _isExecuting：交给 taskListener 在 onTaskCompleted/Stopped/Error 时同步
        }
    }

    /** */
    fun stopExecuting() {
        if (!_isExecuting.value && !TaskExecutor.isExecuting() && !RecognitionExecutor.isExecuting()) {
            return
        }
        TaskExecutor.stop()
        RecognitionExecutor.stop()
        val cnt = TaskManager.stopAllTasks()
        appendLauncherLog("[${stamp()}] ⏹ 用户点击停止：已请求结束 $cnt 个任务")
        // 不在此处查询 _isExecuting：TaskExecutor.stop 的 cancelAndJoin 在独立协程中异步执行，
        // 此处查 isExecuting() 可能仍返回 true（竞态）。按钮状态交给 listener.onTaskStopped 置 false。
        showSnack(if (cnt > 0) "已停止任务" else "当前没有运行中的任务")
    }

    /**
     * 停止映射到 VD 的目标应用（force-stop），并停止 VD 释放资源
     *
     * 用户在 AppLauncherRow 点击「停止」并经确认弹窗确认后调用。
     * ★资源策略★：目标应用 + VD 同时关闭，释放全部资源，避免 VD 空跑浪费电。
     *   再次点击「启动」时会由 launchAppWithOrientationAdaptation 重新创建 VD 并启动应用。
     *
     * ★执行顺序优化★：
     *   先重置所有 UI 状态（按钮立即变回"启动"+ 预览区标签立即更新），
     *   再异步执行 force-stop + stopVirtualDisplay。
     *   无论后台命令是否成功都保持重置状态，防止按钮卡在"停止"。
     */
    fun stopTargetApp() {
        val pkg = _vdTargetPackage.value
        if (pkg.isNullOrBlank()) {
            showSnack("当前没有映射到 VD 的目标应用")
            return
        }
        // ★先重置全部状态：UI 立即响应
        //   _vdTargetPackage=null → 按钮变回"启动"，预览区显示"未启动"
        //   _isRunning=false → 预览区不再可点击进入全屏，状态标签变灰
        _vdTargetPackage.value = null
        _vdTargetSize.value = null
        _isRunning.value = false
        appendLauncherLog("[${stamp()}] ⏹ 正在停止目标应用 pkg=$pkg 并关闭虚拟显示器 ...")
        // 再异步执行：force-stop 目标应用 + stopVirtualDisplay 释放 VD 资源
        viewModelScope.launch(Dispatchers.IO) {
            val appOk = AppManager.forceStopApp(pkg)
            compositionService.stopVirtualDisplay()
            if (appOk) {
                appendLauncherLog("[${stamp()}] ✓ 已停止目标应用 pkg=$pkg 并关闭虚拟显示器")
            } else {
                appendLauncherLog("[${stamp()}] ✗ 应用停止失败 pkg=$pkg（VD 已关闭，状态已重置）")
            }
            showSnack(if (appOk) "已停止 $pkg 并关闭 VD" else "VD 已关闭，应用停止失败")
        }
    }

    fun startRecognitionTask(
        mode: RecognitionMode,
        template: android.graphics.Bitmap? = null,
        targetText: String? = null,
        threshold: Double = 0.8,
        maxAttempts: Int = 0
    ) {
        val vdDisplayId = compositionService.displayId
        if (vdDisplayId <= 0) {
            showSnack("虚拟显示器未启动，请先点击播放按钮")
            return
        }
        if (RecognitionExecutor.isExecuting()) {
            showSnack("识别任务已在执行中")
            return
        }

        _isExecuting.value = true
        RecognitionExecutor.start(
            compositionService = compositionService,
            scope = viewModelScope,
            mode = mode,
            template = template,
            targetText = targetText,
            threshold = threshold,
            maxAttempts = maxAttempts,
            onLog = { line -> TaskManager.notifyOutput("recognition", line) }
        )
        Log.i(TAG, "RecognitionExecutor started: mode=$mode, text=$targetText, vd=$vdDisplayId")
    }

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
                showSnack(msg)
            }
        }
    }

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
                showSnack(msg)
            }
        }
    }

    suspend fun launchAppWithOrientationAdaptation(
        context: Context,
        packageName: String
    ): Pair<Boolean, String> {
        if (packageName.isBlank()) {
            val r = false to "未选中有效应用"
            appendLauncherLog("[${stamp()}] ✗ ${r.second}, pkg=$packageName")
            showSnack(r.second); return r
        }
        val granted = refreshShizukuStatus()
        if (!granted) {
            val diag = ShizukuManager.diagnoseShizuku(context)
            val detail = ShizukuManager.getDiagnosisText(context, diag)
            val longMsg = "Shizuku 未就绪，无法启动虚拟显示器与 App：\n  $detail"
            val shortMsg = "Shizuku 未就绪：$detail"
            appendLauncherLog("[${stamp()}] ✗ ${longMsg.replace("\n", "  ")}")
            val r = false to shortMsg
            showSnack(r.second); return r
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
            showSnack(r.second); return r
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
            showSnack(r.second); return r
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
            showSnack(success.second)
            appendLauncherLog("[${stamp()}] ✓ 已启动 pkg=$packageName 到 display=$dId（任务隔离结果：$detail）")
            success
        } else {
            val r = false to "App 启动命令失败，请确认目标应用已安装（$packageName）"
            showSnack(r.second)
            appendLauncherLog("[${stamp()}] ✗ ${r.second}")
            r
        }
        if (launchResult.first) {
            _vdTargetPackage.value = packageName
            _vdTargetSize.value = targetW to targetH
        }
        return launchResult
    }

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
