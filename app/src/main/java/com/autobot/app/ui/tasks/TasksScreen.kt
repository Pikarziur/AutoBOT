package com.autobot.app.ui.tasks

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autobot.app.manager.AppManager
import com.autobot.app.manager.ScriptTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 后台任务页面 - Compose 实现
 *
 * 布局分为两种模式：
 *
 * 1. 小窗模式：预览区作为 Column 的一部分占据 weight=3，其余 weight=7 留给 Tab 内容
 *    点击小窗时调用 onToggleFullscreenMonitor 进入全屏模式
 *
 * 2. 全屏模式：以 Box 叠加层覆盖整个界面，背景设为黑色
 *    - DisposableEffect 进入时用 WindowInsetsController 隐藏系统状态栏
 *      并设置 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 行为
 *    - LaunchedEffect 将 Activity 强制锁定为 SCREEN_ORIENTATION_SENSOR_LANDSCAPE 横屏
 *    - 退出全屏时在 onDispose 中还原状态栏显示和原始 requestedOrientation 方向
 *    - BackHandler 拦截返回键退出全屏
 *    - 通过 pointerInput 监听触摸事件，使用 viewToVirtualDisplay 坐标映射算法
 *      将用户在预览 View 上的触摸点转换为虚拟显示器坐标
 *    - 右上角放一个关闭按钮退出全屏
 */
@Composable
fun TasksScreen(
    onPickShFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vm: MonitorViewModel = viewModel()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val frameCount by vm.frameCount.collectAsStateWithLifecycle()
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()

    // 模式选择相关状态
    val selectedMode by vm.selectedMode.collectAsStateWithLifecycle()
    val scriptTasks by vm.scriptTasks.collectAsStateWithLifecycle()
    val selectedScriptTaskId by vm.selectedScriptTaskId.collectAsStateWithLifecycle()
    val isExecuting by vm.isExecuting.collectAsStateWithLifecycle()
    val executeMessage by vm.executeMessage.collectAsStateWithLifecycle()
    val shizukuGranted by vm.shizukuGranted.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 是否处于全屏模式
    var isFullscreen by remember { mutableStateOf(false) }
    val isLandscape by vm.isLandscape.collectAsStateWithLifecycle()

    // 已安装可启动应用列表（初始为空，首次进入后台加载）
    var installedApps by remember { mutableStateOf<List<AppManager.AppInfo>>(emptyList()) }
    // 当前选中的 App；默认值在 LaunchedEffect 中查询淘宝是否安装并赋值
    var selectedApp by remember { mutableStateOf<AppManager.AppInfo?>(null) }
    // 下拉菜单展开状态
    var appMenuExpanded by remember { mutableStateOf(false) }
    // 启动按钮加载中（避免重复点击）
    var launching by remember { mutableStateOf(false) }

    // 通过 movableContentOf 共享预览内容（小窗/全屏间复用同一份 SurfaceView）
    val previewContent = PreviewContent

    // executeMessage 变化时弹 Toast（轻量提示）
    LaunchedEffect(executeMessage) {
        executeMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeExecuteMessage()
        }
    }

    // 初次进入：异步加载本地应用列表 + 定位默认"淘宝"并选中
    LaunchedEffect(Unit) {
        if (!isRunning) {
            vm.startVirtualDisplay()
        }
        scope.launch(Dispatchers.IO) {
            val apps = AppManager.getLaunchableApps(context, includeSystem = true)
            installedApps = apps
            // 默认选择淘宝；若淘宝未安装，取列表第一个作为兜底
            val taobao = apps.firstOrNull { it.packageName == AppManager.DEFAULT_PACKAGE_TAOBAO }
            selectedApp = taobao ?: apps.firstOrNull()
        }
    }

    // 定时刷新帧计数（每 500ms 轮询一次）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            vm.refreshFrameCount()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!isFullscreen) {
            // ============ 小窗模式（可滚动长页面）============
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 预览区（包含预览画面 + App 启动条）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(Color.Black)
                ) {
                // 预览画面：占满剩余可用高度
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // 复用预览内容（点击进入全屏）
                    previewContent(vm, false, { isFullscreen = true })

                    // 小窗左上角：状态信息 + 分辨率方向
                    MonitorStatusOverlay(
                        isRunning = isRunning,
                        frameCount = frameCount,
                        displaySize = displaySize,
                        isLandscape = isLandscape,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )

                    // 小窗右上角：手动切换横竖屏方向按钮（兜底）
                    IconButton(
                        onClick = { vm.toggleDisplayOrientation() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "切换横竖屏",
                            tint = Color.White
                        )
                    }
                }

                // ---------- 预览区下方：App 启动功能 ----------
                // 左侧：App 选择下拉（图标 + 应用名 + 展开箭头）
                // 右侧：蓝色圆角「启动」按钮
                AppLauncherRow(
                    selectedApp = selectedApp,
                    installedApps = installedApps,
                    menuExpanded = appMenuExpanded,
                    onMenuToggle = { appMenuExpanded = !appMenuExpanded },
                    onDismissRequest = { appMenuExpanded = false },
                    onSelectApp = { app ->
                        selectedApp = app
                        appMenuExpanded = false
                    },
                    launching = launching,
                    onLaunchClick = {
                        val app = selectedApp ?: return@AppLauncherRow
                        launching = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                // 先检测 App 横竖屏方向 → 必要时重建虚拟显示器 → 再启动到 VD
                                // （返回的提示信息已经通过 vm.executeMessage 触发 Toast）
                                vm.launchAppWithOrientationAdaptation(
                                    context = context,
                                    packageName = app.packageName
                                )
                            } finally {
                                launching = false
                            }
                        }
                    }
                )
            }

            // ---------- 模式选择 + 任务列表区 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                ModeSelectionSection(
                    selectedMode = selectedMode,
                    shizukuGranted = shizukuGranted,
                    scriptTasks = scriptTasks,
                    selectedScriptTaskId = selectedScriptTaskId,
                    isExecuting = isExecuting,
                    onPickShFile = onPickShFile,
                    onSelectMode = vm::selectMode,
                    onSelectScriptTask = vm::selectScriptTask,
                    onDeleteScriptTask = vm::deleteScriptTask,
                    onExecute = vm::executeTask
                )
            }

            // 任务日志区（实时打印运行中的 stdout/stderr / 开始/停止事件）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                TaskLogContent(vm = vm)
            }
            } // close scroll Column
        } else {
            // ============ 全屏模式 ============
            // 全屏区域占据整个 Column（Column 本身即 fillMaxSize）
            FullscreenMonitor(
                vm = vm,
                displaySize = displaySize,
                isLandscape = isLandscape,
                onExit = { isFullscreen = false },
                previewContent = previewContent
            )
        }
    }
}

/**
 * 全屏预览容器
 *
 * 关键点：
 * - Box 叠加层覆盖整个界面，背景黑色
 * - DisposableEffect 进入时隐藏状态栏 + 设置瞬态滑动恢复行为
 * - LaunchedEffect 锁定 Activity 为 SENSOR_LANDSCAPE 横屏
 * - onDispose 还原状态栏 + 原始 requestedOrientation
 * - BackHandler 拦截返回键退出全屏
 * - pointerInput 监听触摸事件，viewToVirtualDisplay 坐标映射后注入 ViewModel
 * - 右上角关闭按钮
 */
@Composable
private fun FullscreenMonitor(
    vm: MonitorViewModel,
    displaySize: Pair<Int, Int>,
    isLandscape: Boolean,
    onExit: () -> Unit,
    previewContent: @Composable (MonitorViewModel, Boolean, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val (bufferWidth, bufferHeight) = displaySize

    // 拦截返回键退出全屏
    BackHandler(enabled = true) {
        onExit()
    }

    // ---- 全屏进入/退出副作用 ----
    DisposableEffect(isLandscape) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null

        // 记录原始方向
        val originalOrientation = activity?.requestedOrientation

        // 进入：隐藏状态栏 + 瞬态滑动恢复
        controller?.let {
            it.hide(WindowInsetsCompat.Type.statusBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 隐藏导航栏
        controller?.hide(WindowInsetsCompat.Type.navigationBars())

        // 进入：保持屏幕常亮
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 进入：根据内容方向锁定 Activity 方向（替代原来的"强制横屏"，改为跟随 VD 内容方向）
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        onDispose {
            // 退出：还原状态栏 + 导航栏显示
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.show(WindowInsetsCompat.Type.navigationBars())
            // 退出：还原屏幕方向
            if (originalOrientation != null && activity != null) {
                activity.requestedOrientation = originalOrientation
            } else if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            // 退出：清除屏幕常亮
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // ---- 触摸事件监听 + 坐标映射 ----
            .pointerInput(bufferWidth, bufferHeight) {
                var lastX = 0
                var lastY = 0
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val viewX = change.position.x.toInt()
                        val viewY = change.position.y.toInt()

                        // viewToVirtualDisplay 坐标映射
                        val mapped = viewToVirtualDisplay(
                            viewX = viewX,
                            viewY = viewY,
                            viewWidth = size.width,
                            viewHeight = size.height,
                            bufferWidth = bufferWidth,
                            bufferHeight = bufferHeight
                        )

                        if (mapped == null) {
                            // 超出虚拟显示器边界，忽略
                            continue
                        }

                        val (vx, vy) = mapped
                        when (event.type) {
                            PointerEventType.Press -> {
                                lastX = vx
                                lastY = vy
                                vm.onTouchDown(vx, vy)
                            }
                            PointerEventType.Move -> {
                                vm.onTouchMove(lastX, lastY, vx, vy)
                                lastX = vx
                                lastY = vy
                            }
                            PointerEventType.Release -> {
                                vm.onTouchUp(vx, vy)
                            }
                            else -> { /* ignore */ }
                        }
                    }
                }
            }
    ) {
        // 复用 movableContentOf 包装的预览内容
        previewContent(vm, true, onExit)

        // 右上角关闭按钮
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "退出全屏",
                tint = Color.White
            )
        }
    }
}

/**
 * viewToVirtualDisplay 坐标映射算法
 *
 * 核心：
 * 1. 取 viewWidth/bufferWidth 和 viewHeight/bufferHeight 的较小值作为等比缩放比例 scale
 * 2. 计算居中黑边的偏移量 (offsetX, offsetY) = (viewWidth - bufferW*scale) / 2
 * 3. (vx, vy) = ((viewX - offsetX)/scale, (viewY - offsetY)/scale)
 * 4. 边界检查：超出范围返回 null
 *
 * @return Pair(vx, vy) 虚拟显示器坐标；超出范围返回 null
 */
fun viewToVirtualDisplay(
    viewX: Int,
    viewY: Int,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int
): Pair<Int, Int>? {
    if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
        return null
    }
    // 1. 等比缩放：取较小比例，保证整个 buffer 都能容纳进 view
    val scale = minOf(
        viewWidth.toFloat() / bufferWidth.toFloat(),
        viewHeight.toFloat() / bufferHeight.toFloat()
    )
    if (scale <= 0f) return null

    // 2. 计算居中黑边的偏移量
    val bufferW = bufferWidth * scale
    val bufferH = bufferHeight * scale
    val offsetX = (viewWidth - bufferW) / 2f
    val offsetY = (viewHeight - bufferH) / 2f

    // 3. 检查是否落在黑边区域
    if (viewX < offsetX || viewX > offsetX + bufferW ||
        viewY < offsetY || viewY > offsetY + bufferH
    ) {
        return null  // 超出范围忽略
    }

    // 4. 计算虚拟显示器坐标
    val vx = ((viewX - offsetX) / scale).toInt()
    val vy = ((viewY - offsetY) / scale).toInt()

    // 5. 边界裁剪
    val finalVx = vx.coerceIn(0, bufferWidth - 1)
    val finalVy = vy.coerceIn(0, bufferHeight - 1)

    return finalVx to finalVy
}

/**
 * 小窗模式左上角状态信息（运行状态 + 帧数 + 分辨率方向）
 */
@Composable
private fun MonitorStatusOverlay(
    isRunning: Boolean,
    frameCount: Long,
    displaySize: Pair<Int, Int>,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val (w, h) = displaySize
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isRunning) "运行中" else "未启动",
                color = if (isRunning) Color(0xFF4CAF50) else Color(0xFFFF9800),
                fontSize = 11.sp
            )
            Text(
                text = "帧: $frameCount",
                color = Color.White,
                fontSize = 10.sp
            )
            Text(
                text = if (isLandscape) "横屏 ${w}x${h}" else "竖屏 ${w}x${h}",
                color = Color(0xFFB3E5FC),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * 任务日志区域块：实时打印运行中任务的 stdout/stderr / 开始 / 完成 / 停止 / 错误等事件
 *
 * 特性：
 * - 顶部标题栏：「任务日志」+「运行中: N」徽章 + 右侧清空按钮
 * - 主体：LazyColumn 显示 vm.taskLogs 列表，每行使用等宽字体
 * - 自动滚动到底：当日志新增时，`collectAsStateWithLifecycle` 触发重绘，
 *   通过 `LaunchedEffect(logs.size)` 自动 `animateScrollToItem(lastIndex)`
 * - 日志内容区分 [OUT] / [ERR] / ✓ / ✗ / ⏹ 等状态颜色
 */
@Composable
private fun TaskLogContent(
    vm: MonitorViewModel,
    modifier: Modifier = Modifier
) {
    val logs by vm.taskLogs.collectAsStateWithLifecycle()
    val runningCount = remember { mutableStateOf(com.autobot.app.manager.TaskManager.getRunningTasks().size) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 定时刷新「运行中任务数」（显示在顶部徽章）
    LaunchedEffect(Unit) {
        while (true) {
            runningCount.value = com.autobot.app.manager.TaskManager.getRunningTasks().size
            kotlinx.coroutines.delay(1000)
        }
    }

    // 日志新增一条自动滚动到底部（最后一条）
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "任务日志",
                    style = MaterialTheme.typography.titleMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "运行中: ${runningCount.value}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // 清空日志按钮
            androidx.compose.material3.TextButton(
                onClick = { vm.clearLogs() }
            ) {
                Text("清空")
            }
        }

        // 日志主体：黑底 + 等宽字体，模拟终端
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
                .background(
                    color = Color(0xFF121212),
                    shape = MaterialTheme.shapes.small
                )
        ) {
            if (logs.isEmpty()) {
                // 空态
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "等待执行任务...",
                        color = Color(0xFFA0A0A0),
                        fontSize = 12.sp
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(logs) { line ->
                        LogLineItem(line = line)
                    }
                }
            }
        }
    }
}

/**
 * 单条日志行：根据内容着色
 *  - [OUT] → 白色
 *  - [ERR] / [EXCEPTION] / ✗ → 红色
 *  - ✓ → 绿色
 *  - ⏹ → 橙色
 *  - 其他 → 浅灰
 */
@Composable
private fun LogLineItem(line: String) {
    val color = when {
        line.contains("[ERR]") || line.contains("[EXCEPTION]") || line.contains("✗") -> Color(0xFFFF5252)
        line.contains("✓") -> Color(0xFF69F0AE)
        line.contains("⏹") -> Color(0xFFFFAB40)
        line.contains("[OUT]") -> Color(0xFFE0E0E0)
        else -> Color(0xFFBBDEFB)
    }
    Text(
        text = line,
        color = color,
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
    )
}

/**
 * 单个任务条目
 */
@Composable
private fun TaskItemRow(
    task: com.autobot.app.model.TaskInfo,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "ID: ${task.id} · ${task.status.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (task.status == com.autobot.app.model.TaskStatus.RUNNING) {
                    androidx.compose.material3.TextButton(onClick = onStop) {
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text(
                text = task.command,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Text(
                text = "开始: ${task.getStartTimeText()} · 时长: ${task.getDurationText()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 从 Context 找到 Activity
 */
private fun android.content.Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 预览区下方：App 启动条
 *  - 左侧：图标 + 应用名下拉框（自由切换为手机其他 App）
 *  - 右侧：蓝色、圆角、文字为「启动」的按钮
 *
 * @param selectedApp     当前选中的 App（默认淘宝）
 * @param installedApps   本地可启动应用列表
 * @param menuExpanded    下拉菜单是否展开
 * @param onMenuToggle    用户点击左侧框时：切换展开/收起
 * @param onDismissRequest 下拉框被外界关闭（点击外部）
 * @param onSelectApp     用户在下拉中选中某个 App
 * @param launching       启动按钮是否处于"加载中"（防止重复点击）
 * @param onLaunchClick   启动按钮点击回调
 */
@Composable
private fun AppLauncherRow(
    selectedApp: AppManager.AppInfo?,
    installedApps: List<AppManager.AppInfo>,
    menuExpanded: Boolean,
    onMenuToggle: () -> Unit,
    onDismissRequest: () -> Unit,
    onSelectApp: (AppManager.AppInfo) -> Unit,
    launching: Boolean,
    onLaunchClick: () -> Unit
) {
    // 启动按钮的颜色：蓝色主题（与全局 colorScheme.primary 一致）
    val launchButtonBlue = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)  // 白色底
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------- 左侧：App 选择下拉框 ----------------
        Box(
            modifier = Modifier.weight(1f)
        ) {
            // 选中态展示（点击可展开下拉）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = installedApps.isNotEmpty(), onClick = onMenuToggle),
                color = MaterialTheme.colorScheme.surfaceVariant,  // 浅灰底
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // App 图标（AndroidView 包裹 ImageView，方便显示 Drawable）
                    AppIconDrawable(
                        icon = selectedApp?.icon,
                        modifier = Modifier.size(26.dp)
                    )

                    // App 名 + 包名 / 占位文案
                    Text(
                        text = selectedApp?.appName ?: "加载中…",
                        color = MaterialTheme.colorScheme.onSurface,  // 黑色文字
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    // 展开箭头
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "选择应用",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant  // 灰色箭头
                    )
                }
            }

            // 下拉菜单：列出本地全部可启动应用（滚动）
            DropdownMenu(
                expanded = menuExpanded && installedApps.isNotEmpty(),
                onDismissRequest = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                installedApps.forEach { app ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppIconDrawable(
                                    icon = app.icon,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selectedApp?.packageName == app.packageName) {
                                    Text(
                                        text = "✓",
                                        color = launchButtonBlue,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        },
                        onClick = { onSelectApp(app) }
                    )
                }
            }
        }

        // ---------------- 右侧：蓝色圆角「启动」按钮 ----------------
        Button(
            onClick = onLaunchClick,
            enabled = selectedApp != null && !launching,
            modifier = Modifier,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = launchButtonBlue,
                disabledContainerColor = launchButtonBlue.copy(alpha = 0.45f),
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = 0.8f)
            )
        ) {
            Text(
                text = if (launching) "启动中" else "启动",
                fontSize = 15.sp
            )
        }
    }
}

/**
 * Drawable 图标 -> AndroidView(ImageView)
 * Compose 原生 Image 难以直接显示系统 Drawable，用 AndroidView 包一下最稳定。
 */
@Composable
private fun AppIconDrawable(
    icon: Drawable?,
    modifier: Modifier = Modifier
) {
    val defaultSizeDp = 24.dp
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        },
        update = { view ->
            if (icon != null) {
                view.setImageDrawable(icon)
            } else {
                // 无图标时显示一个浅灰占位
                view.setImageDrawable(null)
                view.setBackgroundColor(0xFFE0E0E0.toInt())
            }
        }
    )
}

// ============================================================================
// 模式选择区域块
// ============================================================================

/**
 * 模式选择区域块（SH-ADB / 截图识别 单选 + 底部执行任务按钮）
 *
 * 关键交互：
 * - 两个模式单选，选中另一个时当前模式整体变灰不可选
 * - SH-ADB 模式：标题下方显示任务列表选择 + SH 文件导入按钮
 * - 截图识别模式：占位文案"功能开发中"
 * - 底部居中蓝色圆角「执行任务」按钮，联动当前选中模式
 */
@Composable
private fun ModeSelectionSection(
    selectedMode: MonitorViewModel.TaskMode,
    shizukuGranted: Boolean,
    scriptTasks: List<ScriptTaskManager.ScriptTask>,
    selectedScriptTaskId: String?,
    isExecuting: Boolean,
    onPickShFile: () -> Unit,
    onSelectMode: (MonitorViewModel.TaskMode) -> Unit,
    onSelectScriptTask: (String?) -> Unit,
    onDeleteScriptTask: (String) -> Unit,
    onExecute: () -> Unit
) {
    // 主题颜色：蓝色统一从 colorScheme.primary 取
    val accentBlue = MaterialTheme.colorScheme.primary
    val sectionBg = MaterialTheme.colorScheme.surfaceVariant
    val enabledText = MaterialTheme.colorScheme.onSurface
    val disabledText = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(sectionBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ========== 模式 1：SH-ADB ==========
        val isShAdbActive = selectedMode == MonitorViewModel.TaskMode.SH_ADB
        ModeCard(
            title = "SH-ADB 模式",
            subtitle = "通过 SH 文件执行 ADB 指令" +
                    if (!shizukuGranted) "（Shizuku 未授权）" else "",
            selected = isShAdbActive,
            enabled = true,  // 始终可选
            onClick = { onSelectMode(MonitorViewModel.TaskMode.SH_ADB) },
            accentColor = accentBlue,
            enabledContentColor = enabledText,
            disabledContentColor = disabledText
        ) {
            // 子内容：任务列表 + SH 文件按钮（仅本模式启用时可用）
            ShAdbModeContent(
                enabled = isShAdbActive,
                scriptTasks = scriptTasks,
                selectedScriptTaskId = selectedScriptTaskId,
                onSelectScriptTask = onSelectScriptTask,
                onDeleteScriptTask = onDeleteScriptTask,
                onPickShFile = onPickShFile,
                accentColor = accentBlue
            )
        }

        // ========== 模式 2：截图识别（占位）==========
        val isScreenshotActive = selectedMode == MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION
        ModeCard(
            title = "截图识别模式",
            subtitle = "通过截图识别元素执行自动化（开发中）",
            selected = isScreenshotActive,
            enabled = true,  // 可选，但内部功能占位
            onClick = { onSelectMode(MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION) },
            accentColor = accentBlue,
            enabledContentColor = enabledText,
            disabledContentColor = disabledText
        ) {
            // 占位内容
            ScreenshotModePlaceholder(
                enabled = isScreenshotActive,
                disabledContentColor = disabledText
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ========== 底部：执行任务按钮 ==========
        Button(
            onClick = onExecute,
            enabled = !isExecuting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentBlue,
                disabledContainerColor = accentBlue.copy(alpha = 0.5f),
                contentColor = Color.White,
                disabledContentColor = Color.White
            )
        ) {
            Text(
                text = if (isExecuting) "执行中…" else "执行任务",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * 模式卡片容器（标题 + 单选指示器 + 子内容）
 *
 * 当模式被禁用（其他模式选中）时整体变灰：
 * - 单选按钮图标变灰
 * - 标题/副标题文字变灰
 * - 子内容（ShAdbModeContent / ScreenshotModePlaceholder）通过 enabled=false 自行处理
 *
 * @param selected   当前模式是否被选中
 * @param enabled    当前模式是否可点击切换（这里始终 true，因为需求是单选互斥）
 * @param onClick    点击切换模式
 */
@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
    enabledContentColor: Color,
    disabledContentColor: Color,
    content: @Composable () -> Unit
) {
    // 当前模式的整体"激活度"：选中时 enabled=true，未选中时 enabled=false（变灰）
    val isActive = selected
    val titleColor = if (isActive) enabledContentColor else disabledContentColor
    val subtitleColor = disabledContentColor
    val radioTint = if (isActive) accentColor else disabledContentColor

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        color = if (isActive) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        },
        shape = RoundedCornerShape(10.dp),
        tonalElevation = if (isActive) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 标题行：单选按钮 + 标题 + 副标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (selected) Icons.Default.RadioButtonChecked
                                  else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = radioTint,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = titleColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        color = subtitleColor,
                        fontSize = 11.sp
                    )
                }
            }

            // 子内容（仅在选中时才启用交互）
            content()
        }
    }
}

/**
 * SH-ADB 模式子内容：
 *   - 任务列表选择（可选、可删除）
 *   - 右侧 SH 文件按钮（SAF 选择本地文件）
 */
@Composable
private fun ShAdbModeContent(
    enabled: Boolean,
    scriptTasks: List<ScriptTaskManager.ScriptTask>,
    selectedScriptTaskId: String?,
    onSelectScriptTask: (String?) -> Unit,
    onDeleteScriptTask: (String) -> Unit,
    onPickShFile: () -> Unit,
    accentColor: Color
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    val enabledAlpha = if (enabled) 1f else 0.4f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp)  // 缩进对齐标题文字
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 左侧：任务下拉选择
            Box(
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled && scriptTasks.isNotEmpty()) {
                            dropdownExpanded = !dropdownExpanded
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = selectedScriptTaskId
                                ?.let { id -> scriptTasks.find { it.id == id }?.name }
                                ?: "选择任务",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = enabledAlpha),
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = enabledAlpha),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = dropdownExpanded && enabled && scriptTasks.isNotEmpty(),
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    if (scriptTasks.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无任务，请先导入 SH 文件", fontSize = 13.sp) },
                            onClick = { dropdownExpanded = false }
                        )
                    } else {
                        scriptTasks.forEach { task ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = task.name,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (task.id == selectedScriptTaskId) {
                                            Text(
                                                text = "✓",
                                                color = accentColor,
                                                fontSize = 14.sp,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                        }
                                        // 删除按钮
                                        IconButton(
                                            onClick = {
                                                onDeleteScriptTask(task.id)
                                                if (selectedScriptTaskId == task.id) {
                                                    onSelectScriptTask(null)
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "删除任务",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onSelectScriptTask(task.id)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 右侧：SH 文件按钮（SAF 选择本地 .sh 文件）
            OutlinedIconButton(
                text = "SH 文件",
                icon = Icons.Default.Add,
                enabled = enabled,
                onClick = onPickShFile,
                tint = accentColor
            )
        }

        // 任务列表为空时的提示
        if (scriptTasks.isEmpty() && enabled) {
            Text(
                text = "暂无任务，点击「SH 文件」从手机本地导入 .sh 脚本",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

/**
 * 截图识别模式占位内容
 */
@Composable
private fun ScreenshotModePlaceholder(
    enabled: Boolean,
    disabledContentColor: Color
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
                   else disabledContentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "功能开发中，敬请期待",
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                   else disabledContentColor,
            fontSize = 13.sp
        )
    }
}

/**
 * 描边图标按钮（带文字）：用于"SH 文件"按钮
 */
@Composable
private fun OutlinedIconButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            tint.copy(alpha = alpha)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = tint.copy(alpha = alpha),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                color = tint.copy(alpha = alpha),
                fontSize = 13.sp
            )
        }
    }
}
