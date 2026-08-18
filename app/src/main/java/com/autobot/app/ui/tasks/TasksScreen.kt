package com.autobot.app.ui.tasks

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台任务页面 - Compose 实现（完美复刻 MAA-Meow BackgroundTaskView 布局）
 *
 * 简化版：固定启动【淘宝】app（com.taobao.taobao），不再提供 App 下拉列表。
 *
 * 整体结构：
 *   Box(fillMaxSize)
 *     └ Column(fillMaxSize + statusBarsPadding + padding)
 *         ├ 预览区 Box(fillMaxWidth + weight(3f))   ← 占总高度 30%
 *         │   └ VirtualDisplayPreview（BoxWithConstraints + 按宽高比计算 Card 尺寸）
 *         ├ Spacer(8.dp)
 *         └ 下方区 Column(fillMaxWidth + weight(7f))  ← 占总高度 70%
 *             └ 淘宝图标 + "淘宝" 文本 + 三角形播放按钮（固定）
 *
 * 全屏模式：Box(fillMaxSize + black) + previewContent + 触摸事件注入
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

    var isSurfaceAvailable by remember { mutableStateOf(false) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }

    // 淘宝 app 信息（图标 + 名称）
    // 异步加载一次即可，加载失败回退到默认图标
    var taobaoIcon by remember { mutableStateOf<Drawable?>(null) }
    var launching by remember { mutableStateOf(false) }

    // executeMessage 变化时弹 Toast
    LaunchedEffect(executeMessage) {
        executeMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeExecuteMessage()
        }
    }

    // 初次进入：启动虚拟显示器 + 加载淘宝图标
    LaunchedEffect(Unit) {
        if (!isRunning) {
            vm.startVirtualDisplay()
        }
        scope.launch(Dispatchers.IO) {
            val appInfo = AppManager.getAppByPackageName(context, AppManager.DEFAULT_PACKAGE_TAOBAO)
            if (appInfo != null) {
                taobaoIcon = appInfo.icon
            }
        }
    }

    // 定时刷新帧计数
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            vm.refreshFrameCount()
        }
    }

    // 可复用的预览内容（movableContentOf 包装，小窗/全屏切换时复用同一 SurfaceView）
    val previewContent = remember {
        androidx.compose.runtime.movableContentOf {
            PreviewContent(
                vm = vm,
                isFullscreen = isFullscreen,
                onSurfaceAvailable = { isSurfaceAvailable = true },
                onSurfaceDestroyed = { isSurfaceAvailable = false }
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!isFullscreen) {
            // ============ 小窗模式（复刻 MAA-Meow BackgroundTaskView） ============
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 8.dp)
            ) {
                // ---------- 预览图区域：占总高度 30% ----------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(3f)
                ) {
                    VirtualDisplayPreview(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                // 记录预览区在窗口中的位置（用于画中画，此处保留接口）
                                val bounds = coords.boundsInWindow()
                                val next = Rect(
                                    bounds.left.toInt(),
                                    bounds.top.toInt(),
                                    bounds.right.toInt(),
                                    bounds.bottom.toInt(),
                                )
                                if (!next.isEmpty && next != previewBounds) {
                                    previewBounds = next
                                }
                            },
                        isRunning = isRunning,
                        isSurfaceAvailable = isSurfaceAvailable,
                        displaySize = displaySize,
                        onClick = { isFullscreen = true }
                    ) {
                        previewContent()
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---------- 下方业务区：占总高度 70% ----------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(7f)
                ) {
                    TaobaoLauncherRow(
                        taobaoIcon = taobaoIcon,
                        launching = launching,
                        onLaunchClick = {
                            if (launching) return@TaobaoLauncherRow
                            launching = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    vm.launchAppWithOrientationAdaptation(
                                        context = context,
                                        packageName = AppManager.DEFAULT_PACKAGE_TAOBAO
                                    )
                                } finally {
                                    launching = false
                                }
                            }
                        }
                    )

                    // 状态信息（运行状态 + 帧数 + 分辨率方向）
                    MonitorStatusCard(
                        isRunning = isRunning,
                        frameCount = frameCount,
                        displaySize = displaySize,
                        isLandscape = isLandscape,
                        onToggleOrientation = { vm.toggleDisplayOrientation() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }
        } else {
            // ============ 全屏模式 ============
            FullscreenMonitor(
                vm = vm,
                displaySize = displaySize,
                isLandscape = isLandscape,
                onExit = { isFullscreen = false },
                previewContent = {
                    previewContent()
                }
            )
        }
    }
}

/**
 * 虚拟显示器预览组件（完美复刻 MAA-Meow VirtualDisplayPreview）
 *
 * 关键点：
 * 1. BoxWithConstraints 拿到父容器最大宽高
 * 2. 按虚拟显示器宽高比（bufferWidth/bufferHeight）计算 Card 尺寸
 *    - 若用高度算出的宽度 <= 最大宽度：高度优先，宽度按比例
 *    - 否则：宽度优先，高度按比例
 * 3. Card + shape medium + elevation 4dp + clickable
 * 4. 内部 Box(fillMaxSize) 放 content
 * 5. 未运行/surface 不可用时显示半透明遮罩 + 提示文字
 * 6. 右上角状态指示器
 */
@Composable
private fun VirtualDisplayPreview(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isSurfaceAvailable: Boolean,
    displaySize: Pair<Int, Int>,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val (bufferWidth, bufferHeight) = displaySize
    val aspectRatio = if (bufferWidth > 0 && bufferHeight > 0) {
        bufferWidth.toFloat() / bufferHeight.toFloat()
    } else {
        16f / 9f  // 兜底
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxWidthPx = maxWidth
        val maxHeightPx = maxHeight
        val widthFromHeight = maxHeightPx * aspectRatio
        val heightFromWidth = maxWidthPx / aspectRatio
        val (cardWidth, cardHeight) = if (widthFromHeight <= maxWidthPx) {
            widthFromHeight to maxHeightPx
        } else {
            maxWidthPx to heightFromWidth
        }

        Card(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                when {
                    !isRunning -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "虚拟显示器未启动",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    !isSurfaceAvailable -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "等待 Surface...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 右上角状态指示器
                val (dotColor, label) = when {
                    isRunning && isSurfaceAvailable -> {
                        Color(0xFF4CAF50) to "运行中"
                    }
                    isRunning -> {
                        Color(0xFFFF9800) to "等待 Surface"
                    }
                    else -> {
                        Color(0xFF9E9E9E) to "未启动"
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
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
    previewContent: @Composable () -> Unit
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
        previewContent()

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
 * 淘宝启动条（固定显示，不再支持下拉切换）
 *
 * 左侧：淘宝图标 + "淘宝" 文本（图标加载失败时显示默认占位色块）
 * 右侧：三角形播放按钮（蓝色填充圆形 IconButton）
 *
 * 与原 AppLauncherRow 的差异：
 *  - 移除 installedApps / DropdownMenu / DropdownMenuItem，UI 简化为单行
 *  - 包名固定为 com.taobao.taobao，由调用方在 onLaunchClick 时传入
 */
@Composable
private fun TaobaoLauncherRow(
    taobaoIcon: Drawable?,
    launching: Boolean,
    onLaunchClick: () -> Unit
) {
    val accentBlue = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------- 左侧：淘宝图标 + 文本（不可点击） ----------------
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                    icon = taobaoIcon,
                    modifier = Modifier.size(26.dp)
                )

                Text(
                    text = "淘宝",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ---------------- 右侧：三角形播放按钮 ----------------
        // 蓝色填充圆形按钮，中心为三角形 PlayArrow 图标
        IconButton(
            onClick = onLaunchClick,
            enabled = !launching,
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (!launching) accentBlue
                    else accentBlue.copy(alpha = 0.4f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "启动淘宝",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 状态信息卡片
 */
@Composable
private fun MonitorStatusCard(
    isRunning: Boolean,
    frameCount: Long,
    displaySize: Pair<Int, Int>,
    isLandscape: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (w, h) = displaySize

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isRunning) "运行中" else "未启动",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRunning) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "帧: $frameCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isLandscape) "横屏 ${w}x${h}" else "竖屏 ${w}x${h}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onToggleOrientation,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenRotation,
                    contentDescription = "切换横竖屏",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
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
