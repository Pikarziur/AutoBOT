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
import com.autobot.app.manager.AppManager
import com.autobot.app.manager.ScriptTaskManager
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskStatus
import com.autobot.app.model.TaskType
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

    /**
     * 当前 VD 中已启动 & 隔离到前台的目标 App 包名。
     *   - 例如："com.taobao.taobao"
     *   - 未启动目标 App 时为 null。
     *   - executeShAdbTask 执行前据此判断是否需要先把目标 App 启动到 VD 前台。
     */
    private val _vdTargetPackage = MutableStateFlow<String?>(null)
    val vdTargetPackage: StateFlow<String?> = _vdTargetPackage.asStateFlow()

    /**
     * 当前 VD 分辨率（执行脚本时注入到 AUTOBOT_VD_WIDTH / AUTOBOT_VD_HEIGHT 环境变量，
     * 便于脚本中按 VD 坐标系计算点击位置，避免写死主屏 1080p 坐标打到主屏上）。
     */
    private val _vdTargetSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val vdTargetSize: StateFlow<Pair<Int, Int>?> = _vdTargetSize.asStateFlow()

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
     * 诊断日志流（Shizuku 状态 / VD 启动 / 错误信息等，UI 暂不展示）
     * - 每行格式：[HH:mm:ss] <symbol> <message>
     * - 用 List<String> 保留最近 LOG_MAX_LINES 行，避免内存无限增长
     *
     * 注：此流不再由 UI 直接消费；UI 日志 Tab 只显示 [scriptLogs]（脚本执行日志）。
     *      保留 appendLog() 调用点是为了在 Logcat 之外保留一份运行时诊断记录，便于排查。
     */
    private val _taskLogs = MutableStateFlow<List<String>>(emptyList())
    val taskLogs: StateFlow<List<String>> = _taskLogs.asStateFlow()

    /**
     * 脚本执行日志流（UI 日志 Tab 专用，仅由 taskListener 写入）
     * - 仅包含脚本任务的生命周期与 stdout/stderr 输出
     * - 不混入 Shizuku 诊断 / VD 启动等非脚本日志
     * - 保留最多 LOG_MAX_LINES 行，避免内存无限增长
     */
    private val _scriptLogs = MutableStateFlow<List<String>>(emptyList())
    val scriptLogs: StateFlow<List<String>> = _scriptLogs.asStateFlow()

    // 注：.sh 文件选择器相关 state（shFilePickerVisible / shFileList / isListingShFiles）已移除
    // 脚本来源改为 app 内部 assets/scripts/ 预置，不再需要运行时扫描 /sdcard
    private val logLock = Any()
    private val logSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * TaskManager 事件监听器：将脚本任务生命周期和输出转为 UI 日志
     * 注：所有事件均写入 [_scriptLogs]（脚本执行专用流），不污染 [_taskLogs]
     */
    private val taskListener = object : TaskManager.TaskListener {
        override fun onTaskStarted(task: com.autobot.app.model.TaskInfo) {
            appendScriptLog("[${stamp()}] ⟶ 启动任务『${task.name}』 (id=${task.id.substring(0, 4)} type=${task.type})")
            // ★启动时打印 VD 注入摘要：让用户从日志直接确认"脚本执行映射到了哪块虚拟显示器、分辨率、目标 App"
            //   - 这 4 项 = TaskManager.submitTask 时 extraEnv 注入的 AUTOBOT_VD_* 环境变量
            //   - 若 displayId=0 或空 → 命令默认落到 display 0 = 主屏 = 就是当前"打 AutoBOT"的症状
            val env = task.env
            val dId = task.displayId.takeIf { it > 0 } ?: env["AUTOBOT_VD_DISPLAY_ID"]?.toIntOrNull() ?: 0
            val vW = env["AUTOBOT_VD_WIDTH"]  ?: "-"
            val vH = env["AUTOBOT_VD_HEIGHT"] ?: "-"
            val pkg = env["AUTOBOT_TARGET_PACKAGE"] ?: "(未指定)"
            val okHint = if (dId > 0 && vW != "-") "✅" else "⚠️"
            appendScriptLog("[${stamp()}] $okHint VD 注入：display=$dId, size=${vW}x$vH, target=$pkg" +
                    if (dId <= 0) "  → 注：display=0 将落到主屏，操作会打 AutoBOT！" else "")
        }
        override fun onTaskOutput(task: com.autobot.app.model.TaskInfo, line: String) {
            appendScriptLog("[${stamp()}] [${task.id.substring(0, 4)}] $line")
        }
        override fun onTaskCompleted(task: com.autobot.app.model.TaskInfo) {
            val duration = task.getDurationText()
            appendScriptLog("[${stamp()}] ✓ 任务『${task.name}』完成 (exit=${task.exitCode}, duration=$duration)")
        }
        override fun onTaskStopped(task: com.autobot.app.model.TaskInfo) {
            appendScriptLog("[${stamp()}] ⏹ 任务『${task.name}』被停止 (id=${task.id.substring(0, 4)})")
        }
        override fun onTaskError(task: com.autobot.app.model.TaskInfo, error: String) {
            appendScriptLog("[${stamp()}] ✗ 任务『${task.name}』出错: $error")
        }
    }

    private fun stamp(): String = logSdf.format(Date())

    /** 追加一行诊断日志（Shizuku/VD 等非脚本输出，UI 不展示），线程安全 */
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

    /** 追加一行脚本执行日志到 [_scriptLogs]（UI 日志 Tab 显示），最多保留 500 行，线程安全 */
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
     * 追加"启动目标 App / VD 控制链路"的日志到【脚本执行日志】流（UI 日志 Tab 可见）。
     * - 这部分是用户最关心的"淘宝启动为啥失败"；之前用 appendLog 写到 _taskLogs（诊断流，UI 不展示），
     *   Toast 又被截断 → 用户完全看不到完整错误。
     * - 现在集中写入 UI 日志 Tab：LazyColumn 任意长度滚动 + 顶部『复制』按钮直接复制全文。
     * - 线程安全（走 appendScriptLog 的 logLock）。
     */
    private fun appendLauncherLog(line: String) = appendScriptLog(line)

    /**
     * 供 UI 层（TaobaoLauncherRow）在捕获到启动崩溃 Exception 时，
     * 把完整异常信息追加到『日志 Tab』，方便用户直接复制反馈。
     */
    fun reportLauncherCrash(fullText: String) {
        val stamp = stamp()
        fullText.lineSequence().forEachIndexed { idx, ln ->
            appendScriptLog(if (idx == 0) "[$stamp] ✗ $ln" else "[$stamp]   $ln")
        }
    }

    /** 供 UI 层临时塞一条 Toast 短提示（不写日志），避免 UI 层直接碰 private _executeMessage */
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
        // 加载已持久化的 SH 脚本任务列表
        refreshScriptTasks()
        // 注册 TaskManager 监听器：实时将脚本执行日志推给 UI
        TaskManager.addListener(taskListener)
        // 欢迎日志写入脚本日志流（UI 日志 Tab 初始展示用）
        appendScriptLog("[${stamp()}] 日志区就绪：SH-ADB 任务执行后 stdout/stderr 将实时显示在此")
    }

    /**
     * 清空 UI 脚本日志（仅清空 [_scriptLogs] 显示，不影响 TaskManager 的历史）
     * 同时清空诊断日志 [_taskLogs]，避免运行久后内存累积
     */
    fun clearLogs() {
        synchronized(logLock) {
            _taskLogs.value = emptyList()
            _scriptLogs.value = emptyList()
            appendScriptLog("[${stamp()}] 日志已清空")
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
        appendLauncherLog("[${stamp()}] Shizuku 诊断：$text (code=$diag)")
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

    // 注：importScript / deleteScriptTask / showShFilePicker / hideShFilePicker / importScriptFromPath
    // 已全部移除（脚本来源改为 app 内部 assets/scripts/ 预置，由 ScriptTaskManager.loadBundledScripts() 启动时装载）

    /**
     * 消费执行消息（UI 显示后调用，清空避免重复显示）
     */
    fun consumeExecuteMessage() {
        _executeMessage.value = null
    }

    /**
     * 执行任务（联动当前选中的模式）
     *
     * 前置约束（两个模式共用）：
     *   - 任务/截图识别执行的动作必须只映射到 VD（虚拟显示器）
     *   - 所以 **执行前必须确认 VD 已启动（compositionService.displayId > 0）**
     *   - 未启动时给出提示（"请先点击播放按钮启动虚拟显示器"），阻止执行
     *
     * - SH-ADB 模式：调用 TaskManager.submitTask 执行选中的 SH 脚本
     *   （通过 ShellExecutor.executeScript → Shizuku 执行 SH 中的 ADB 指令；
     *     同时注入 AUTOBOT_VD_DISPLAY_ID 环境变量，am/tap/input 等脚本命令
     *     可通过 `--display $AUTOBOT_VD_DISPLAY_ID` 定向到 VD）
     * - 截图识别模式：占位提示"功能开发中"
     */
    fun executeTask() {
        if (_isExecuting.value) {
            Log.w(TAG, "Task already executing")
            return
        }

        // -------- VD 就绪检查（两个模式共用） --------
        // 约束：两个模式执行的任务"只映射在 VD 执行"
        //   因此未启动 VD 时一律拒绝执行，并给出与 Fullscreen 入口相同的提示文案。
        val vdDisplayId = compositionService.displayId
        if (vdDisplayId <= 0) {
            _executeMessage.value = "请先点击播放按钮启动虚拟显示器"
            return
        }

        when (_selectedMode.value) {
            TaskMode.SH_ADB -> executeShAdbTask(vdDisplayId)
            TaskMode.SCREENSHOT_RECOGNITION -> {
                _executeMessage.value = "截图识别模式开发中，敬请期待"
                Log.i(TAG, "Screenshot recognition mode not implemented yet")
            }
        }
    }

    /**
     * SH-ADB 模式：执行选中的 SH 脚本任务
     *
     * 前置保障（MAA-Meow 相同风格的"操作只映射 VD 目标 App"）：
     *   1. VD 必须运行（executeTask 公共入口已通过 displayId>0 检查）
     *   2. 必须已启动"目标 App=淘宝"到 VD 前台（通过 TaskIsolator 从 display 0 移走）
     *      未启动则**自动调用 launchAppWithOrientationAdaptation(DEFAULT_PACKAGE_TAOBAO)**
     *   3. 脚本执行时注入 AUTOBOT_VD_* 环境变量，脚本按这些变量带 --display 参数
     *      - AUTOBOT_VD_DISPLAY_ID：`am start --display $VDID ...` 启动到 VD
     *        + `input --display $VDID tap x y` 点 VD 画面（不会打主屏 AutoBOT）
     *      - AUTOBOT_VD_WIDTH / AUTOBOT_VD_HEIGHT：VD 分辨率（坐标计算基准）
     *      - AUTOBOT_TARGET_PACKAGE：目标 App 包名
     *
     * @param vdDisplayId 已就绪的虚拟显示器 ID（> 0），注入 AUTOBOT_VD_DISPLAY_ID 环境变量
     */
    private fun executeShAdbTask(vdDisplayId: Int) {
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

        // 若 VD 未启动目标 App，则在 submitTask 之前先启动+隔离淘宝（launchAppWithOrientationAdaptation
        // 是 suspend，必须放到 viewModelScope.launch 的协程体内；不能用 runBlocking）
        val targetPkg = _vdTargetPackage.value
            ?: com.autobot.app.manager.AppManager.DEFAULT_PACKAGE_TAOBAO
        val needsRelaunch = _vdTargetPackage.value == null
        if (needsRelaunch) {
            _executeMessage.value = "自动启动目标 App (淘宝) 到虚拟显示器，请稍候..."
            appendLauncherLog("[${stamp()}] ⟶ 检测到 VD 前台无目标 App，自动启动到 display=$vdDisplayId")
        }

        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // -------- 目标 App 前置启动 & 隔离到 VD 前台（协程内） --------
                // MAA-Meow 的点击/坐标都以 VD 上的目标 App 为目标。
                // 如果用户没有先点"▶ 启动"按钮启动淘宝到 VD，脚本里 `input tap` 仍然会打在
                // display 0 前台 App（AutoBOT 主界面）上。所以这里做**兜底自动启动/隔离**。
                if (needsRelaunch) {
                    val (ok, msg) = launchAppWithOrientationAdaptation(
                        context = getApplication<android.app.Application>(),
                        packageName = targetPkg
                    )
                    if (!ok) {
                        _executeMessage.value = "目标 App 启动失败，任务中止：$msg"
                        appendLauncherLog("[${stamp()}] ✗ 任务中止：目标 App 未启动到 VD：$msg")
                        return@launch
                    }
                }

                // -------- 构造注入到 .sh 脚本的环境变量 --------
                // 注：vdTargetSize / displaySize 可能在兜底启动后被更新，必须在 needsRelaunch
                // 分支之后再读取。
                val (vdW, vdH) = _vdTargetSize.value
                    ?: _displaySize.value.takeIf { it.first > 0 }
                    ?: (CompositionService.DEFAULT_WIDTH to CompositionService.DEFAULT_HEIGHT)

                // -------【关键：主屏（手机）物理分辨率 = 用户写脚本时的基准分辨率】-------
                // 用户说"脚本坐标按手机分辨率写的，不想改 .sh"，那就统一主屏真实尺寸作为 BASE：
                //   AUTOBOT_BASE_W x AUTOBOT_BASE_H = display 0 物理分辨率（你写脚本时按这个数写的 tap/swipe）
                // ShellExecutor wrapper 会按 VD_W/BASE_W、VD_H/BASE_H 等比缩放所有坐标
                // 并 clamp 到 [0, VD_W-1] × [0, VD_H-1]，全程不改脚本。
                val (baseW, baseH) = runCatching {
                    val ctx = getApplication<android.app.Application>()
                    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val def = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: error("无主屏")
                    val size = Point().also { def.getRealSize(it) }
                    // 宽小高大（竖屏基准）：如果手机是横屏（w>h），就交换回"宽高一致脚本的习惯"
                    if (size.x > size.y) (size.y to size.x) else (size.x to size.y)
                }.getOrElse {
                    Log.w(TAG, "无法读取主屏尺寸，回退到 VD 基准（=不缩放）", it)
                    vdW to vdH
                }

                val taskEnv = buildMap {
                    put("AUTOBOT_VD_DISPLAY_ID", vdDisplayId.toString())
                    put("AUTOBOT_VD_WIDTH", vdW.toString())
                    put("AUTOBOT_VD_HEIGHT", vdH.toString())
                    put("AUTOBOT_TARGET_PACKAGE", targetPkg)
                    // BASE = 用户手机真实分辨率（写脚本时的坐标基准）
                    put("AUTOBOT_BASE_W", baseW.toString())
                    put("AUTOBOT_BASE_H", baseH.toString())
                }

                // 启动日志中同时打印『缩放系数』（千分比），让你一眼知道这次是否做了 BASE→VD 等比
                // 比如 base=1200x2608, vd=540x960 → sx=450‰, sy≈368‰，脚本 (1200,2608)=(右下) 会缩到 (539,959)
                val sx = if (baseW > 0) vdW * 1000 / baseW else 1000
                val sy = if (baseH > 0) vdH * 1000 / baseH else 1000
                val scalingNote = if (sx == 1000 && sy == 1000) "（基准与 VD 一致，不缩放）"
                                  else "（base=${baseW}x$baseH → vd=${vdW}x$vdH，sx=${sx}‰, sy=${sy}‰）"
                appendLauncherLog("[${stamp()}] ⚙️  坐标缩放开关：$scalingNote  越界点将自动贴边到 VD 边界")

                // 提交到 TaskManager 异步执行（type=SCRIPT 走 ShellExecutor.executeScriptStreaming）
                // - displayId：TaskManager 内部会合并为 AUTOBOT_VD_DISPLAY_ID
                // - extraEnv：vdW/vdH/targetPkg 供脚本按 VD 坐标系操作目标 App
                TaskManager.submitTask(
                    name = task.name,
                    command = task.scriptPath,
                    type = TaskType.SCRIPT,
                    useShizuku = true,
                    displayId = vdDisplayId,
                    extraEnv = taskEnv
                )
                _executeMessage.value = "任务已启动：${task.name}（映射到显示器 #$vdDisplayId）"
                Log.i(TAG,
                    "SH-ADB task submitted: ${task.name} -> ${task.scriptPath}, vd=$vdDisplayId, " +
                            "target=$targetPkg, size=${vdW}x$vdH")
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
                appendLauncherLog("[${stamp()}] ✗ $msg")
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
                appendLauncherLog("[${stamp()}] ✗ $msg")
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
            appendLauncherLog("[${stamp()}] ✗ ${r.second}, pkg=$packageName")
            _executeMessage.value = r.second; return r
        }
        // Shizuku 校验：先刷新一次状态（解决授权后仍显示未授权的缓存问题）
        //  refreshShizukuStatus 内部会把诊断结果写入日志区，方便用户肉眼排查
        val granted = refreshShizukuStatus()
        if (!granted) {
            val diag = ShizukuManager.diagnoseShizuku(context)
            val detail = ShizukuManager.getDiagnosisText(context, diag)
            // Shizuku 诊断文本通常较长（含 Shizuku 服务状态、uid、权限、授予结果），
            // Toast 只显示最简短版本，**完整详情写入日志 Tab 方便滚动 + 复制**
            val longMsg = "Shizuku 未就绪，无法启动虚拟显示器与 App：\n  $detail"
            val shortMsg = "Shizuku 未就绪：$detail"
            appendLauncherLog("[${stamp()}] ✗ ${longMsg.replace("\n", "  ")}")
            val r = false to shortMsg
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
            // 重启动时清理旧的目标 App 记录（等 isolateToVirtualDisplay 成功后再赋值）
            _vdTargetPackage.value = null
            _vdTargetSize.value = null
        }
        appendLauncherLog("[${stamp()}] ⟶ 启动虚拟显示器 ${targetW}x$targetH 并启动 pkg=$packageName")
        val (surface, errMsg) = compositionService.startVirtualDisplay(targetW, targetH)
        if (errMsg.isBlank()) {
            _displaySize.value = targetW to targetH
            _isLandscape.value = compositionService.isLandscape
            _isRunning.value = true
            Log.i(TAG, "VD freshly started at ${targetW}x${targetH} for app $packageName")
        } else {
            val msg = errMsg.ifBlank { "虚拟显示器启动失败，请确认 Shizuku 已授权" }
            appendLauncherLog("[${stamp()}] ✗ $msg")
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
            appendLauncherLog("[${stamp()}] ✗ ${r.second}，waited=${waited * 100}ms")
            _executeMessage.value = r.second; return r
        }

        // 6. 启动 App 到虚拟显示器
        val ok = AppManager.launchApp(context, packageName, dId)
        val launchResult = if (ok) {
            // 7. TaskIsolator.isolateToVirtualDisplay()
            //    am start --display 之后系统可能仍把目标 App 的 task 调度回 display 0（AMS 策略），
            //    造成 VD 里只显示 AutoBOT，input 注入落到 AutoBOT 而非目标 App。
            //    这里等待 1.5s 后 dumpsys 找到 display 0 上残留的目标 task，通过 am stack move-task
            //    （Android 10-）或 am task move-task（Android 11+）移回 VD stack，保证 VD 前台=目标 App。
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
        // 启动/隔离成功后，把目标包名写入 _vdTargetPackage，供 executeShAdbTask 判重 & 注入环境变量
        if (launchResult.first) {
            _vdTargetPackage.value = packageName
            _vdTargetSize.value = targetW to targetH
        }
        return launchResult
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
     * 发送 MSG_TOUCH_DOWN 到 server 进程，server 用 IInputManager.injectInputEvent() 注入 MotionEvent
     * @param vx 虚拟显示器坐标 X
     * @param vy 虚拟显示器坐标 Y
     */
    fun onTouchDown(vx: Int, vy: Int) {
        addTouchMarker(vx.toFloat(), vy.toFloat())
        compositionService.injectTouchDown(vx, vy)
    }

    /**
     * 触摸事件 - 移动
     * 发送 MSG_TOUCH_MOVE 到 server 进程（零进程开销，可直接高频调用）
     */
    fun onTouchMove(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        compositionService.injectTouchMove(fromX, fromY, toX, toY)
    }

    /**
     * 触摸事件 - 抬起
     * 发送 MSG_TOUCH_UP 到 server 进程
     */
    fun onTouchUp(vx: Int, vy: Int) {
        compositionService.injectTouchUp(vx, vy)
    }

    /**
     * 返回键 - 注入 KEYCODE_BACK 到虚拟显示器
     *
     * 发送 MSG_KEY_BACK 到 server 进程，server 端构造 KeyEvent(ACTION_DOWN + ACTION_UP) 一对
     * 通过 IInputManager.injectInputEvent() 注入到 VD，让目标 App（如淘宝）返回上一层。
     *
     * 调用方需自行判断 isRunning：仅在 VD 运行时调用本方法，
     * 未运行时应直接退出全屏（不要把 KEYCODE_BACK 注入到主屏幕）。
     */
    fun onBackPress() {
        compositionService.injectBack()
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
