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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autobot.app.manager.AppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 后台任务页面 - Compose 实现（MAA-Meow 风格）
 *
 * 布局：
 * 1. 小窗模式：顶部虚拟显示器预览 → 下方 App 下拉列表 + 三角形启动按钮
 *    点击预览画面进入全屏模式
 * 2. 全屏模式：Box 叠加层覆盖整个界面，背景黑色
 *    - 隐藏系统状态栏/导航栏
 *    - 锁定 Activity 方向跟随 VD 内容方向
 *    - 触摸事件经 viewToVirtualDisplay 坐标映射后注入虚拟显示器
 *    - 右上角关闭按钮退出全屏
 */
@Composable
fun TasksScreen(
    modifier: Modifier = Modifier
) {
    val vm: MonitorViewModel = viewModel()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val frameCount by vm.frameCount.collectAsStateWithLifecycle()
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()
    val executeMessage by vm.executeMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isFullscreen by remember { mutableStateOf(false) }
    val isLandscape by vm.isLandscape.collectAsStateWithLifecycle()

    // 已安装可启动应用列表
    var installedApps by remember { mutableStateOf<List<AppManager.AppInfo>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<AppManager.AppInfo?>(null) }
    var appMenuExpanded by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }

    val previewContent = PreviewContent

    // executeMessage 变化时弹 Toast
    LaunchedEffect(executeMessage) {
        executeMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeExecuteMessage()
        }
    }

    // 初次进入：启动虚拟显示器 + 加载本地应用列表
    LaunchedEffect(Unit) {
        if (!isRunning) {
            vm.startVirtualDisplay()
        }
        scope.launch(Dispatchers.IO) {
            val apps = AppManager.getLaunchableApps(context, includeSystem = true)
            installedApps = apps
            val taobao = apps.firstOrNull { it.packageName == AppManager.DEFAULT_PACKAGE_TAOBAO }
            selectedApp = taobao ?: apps.firstOrNull()
        }
    }

    // 定时刷新帧计数
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            vm.refreshFrameCount()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!isFullscreen) {
            // ============ 小窗模式 ============
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // ---------- 顶部：虚拟显示器预览 ----------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            displaySize.first.toFloat() / displaySize.second.toFloat()
                        )
                        .background(Color.Black)
                ) {
                    previewContent(vm, false) { isFullscreen = true }

                    // 左上角状态信息
                    MonitorStatusOverlay(
                        isRunning = isRunning,
                        frameCount = frameCount,
                        displaySize = displaySize,
                        isLandscape = isLandscape,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )

                    // 右上角横竖屏切换按钮
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

                // ---------- 下方：App 下拉列表 + 三角形启动按钮 ----------
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
        } else {
            // ============ 全屏模式 ============
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
 * - 隐藏状态栏/导航栏
 * - 锁定 Activity 方向跟随 VD 内容方向
 * - pointerInput 监听触摸事件并映射到虚拟显示器坐标
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

    BackHandler(enabled = true) {
        onExit()
    }

    DisposableEffect(isLandscape) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
        val originalOrientation = activity?.requestedOrientation

        controller?.let {
            it.hide(WindowInsetsCompat.Type.statusBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        controller?.hide(WindowInsetsCompat.Type.navigationBars())

        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.show(WindowInsetsCompat.Type.navigationBars())
            if (originalOrientation != null && activity != null) {
                activity.requestedOrientation = originalOrientation
            } else if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(bufferWidth, bufferHeight) {
                var lastX = 0
                var lastY = 0
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val viewX = change.position.x.toInt()
                        val viewY = change.position.y.toInt()

                        val mapped = viewToVirtualDisplay(
                            viewX = viewX,
                            viewY = viewY,
                            viewWidth = size.width,
                            viewHeight = size.height,
                            bufferWidth = bufferWidth,
                            bufferHeight = bufferHeight
                        )

                        if (mapped == null) continue

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
        previewContent(vm, true, onExit)

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
 * 等比缩放 + 居中黑边偏移，将预览 View 上的触摸点转换为虚拟显示器坐标。
 * 超出虚拟显示器边界返回 null。
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
    val scale = minOf(
        viewWidth.toFloat() / bufferWidth.toFloat(),
        viewHeight.toFloat() / bufferHeight.toFloat()
    )
    if (scale <= 0f) return null

    val bufferW = bufferWidth * scale
    val bufferH = bufferHeight * scale
    val offsetX = (viewWidth - bufferW) / 2f
    val offsetY = (viewHeight - bufferH) / 2f

    if (viewX < offsetX || viewX > offsetX + bufferW ||
        viewY < offsetY || viewY > offsetY + bufferH
    ) {
        return null
    }

    val vx = ((viewX - offsetX) / scale).toInt()
    val vy = ((viewY - offsetY) / scale).toInt()

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
 *
 * 左侧：App 选择下拉框（图标 + 应用名 + 展开箭头）
 * 右侧：三角形播放按钮（蓝色填充圆形 IconButton）
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
    val accentBlue = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------- 左侧：App 选择下拉框 ----------------
        Box(
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = installedApps.isNotEmpty(), onClick = onMenuToggle),
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                    AppIconDrawable(
                        icon = selectedApp?.icon,
                        modifier = Modifier.size(26.dp)
                    )

                    Text(
                        text = selectedApp?.appName ?: "加载中…",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "选择应用",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 下拉菜单：列出本地全部可启动应用
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
                                        color = accentBlue,
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

        // ---------------- 右侧：三角形播放按钮 ----------------
        // 蓝色填充圆形按钮，中心为三角形 PlayArrow 图标
        IconButton(
            onClick = onLaunchClick,
            enabled = selectedApp != null && !launching,
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (selectedApp != null && !launching) accentBlue
                    else accentBlue.copy(alpha = 0.4f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "启动 App",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * Drawable 图标 -> AndroidView(ImageView)
 */
@Composable
private fun AppIconDrawable(
    icon: Drawable?,
    modifier: Modifier = Modifier
) {
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
                view.setImageDrawable(null)
                view.setBackgroundColor(0xFFE0E0E0.toInt())
            }
        }
    )
}
