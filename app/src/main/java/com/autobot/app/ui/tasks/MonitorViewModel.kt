package com.autobot.app.ui.tasks

import android.app.Application
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.ScriptTaskManager
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskType
import com.autobot.app.nativelib.NativeCapturer
import com.autobot.app.service.CompositionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 *   CompositionService → DisplayServiceShizuku → ShizukuBinderWrapper →
 *   IDisplayManager.createVirtualDisplay（shell uid 持有 MANAGE_DISPLAYS 权限）
 *   全程不弹窗、不需要用户运行时确认；前置条件仅为 Shizuku 已授权
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MonitorViewModel"
    }

    private val compositionService = CompositionService()

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

    init {
        // 尝试加载 Native 库
        try {
            NativeCapturer.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library load failed", e)
        }
        // 加载已持久化的 SH 脚本任务列表
        refreshScriptTasks()
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
            val surface = compositionService.startVirtualDisplay(width, height)
            if (surface != null) {
                _displaySize.value = width to height
                _isRunning.value = true
                Log.i(TAG, "VirtualDisplay launched: ${width}x${height}")
            } else {
                Log.e(TAG, "VirtualDisplay launch failed (surface null)")
            }
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

    /**
     * 触摸事件 - 按下
     * @param vx 虚拟显示器坐标 X
     * @param vy 虚拟显示器坐标 Y
     */
    fun onTouchDown(vx: Int, vy: Int) {
        addTouchMarker(vx.toFloat(), vy.toFloat())
        compositionService.injectTouchDown(vx, vy)
    }

    /**
     * 触摸事件 - 移动
     */
    fun onTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        compositionService.injectTouchMove(fromX, fromY, toX, toY)
    }

    /**
     * 触摸事件 - 抬起
     */
    fun onTouchUp(vx: Int, vy: Int) {
        compositionService.injectTouchUp(vx, vy)
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
        super.onCleared()
        compositionService.stopVirtualDisplay()
        _isRunning.value = false
        _previewSurface.value = null
    }
}
