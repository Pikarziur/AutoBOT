package com.autobot.app.ui.tasks

import com.autobot.app.R
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autobot.app.manager.AppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** */
@Composable
fun TasksScreen(
    modifier: Modifier = Modifier
) {
    val vm: MonitorViewModel = viewModel()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()
    val executeMessage by vm.executeMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isFullscreen by remember { mutableStateOf(false) }
    val isLandscape by vm.isLandscape.collectAsStateWithLifecycle()

    LaunchedEffect(isFullscreen) {
        vm.setFullscreen(isFullscreen)
    }

    var isSurfaceAvailable by remember { mutableStateOf(false) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }

    var taobaoIcon by remember { mutableStateOf<Drawable?>(null) }
    var launching by remember { mutableStateOf(false) }

    // 目标应用是否已映射到 VD（成功启动后 vm.vdTargetPackage 不为 null）
    val vdTargetPackage by vm.vdTargetPackage.collectAsStateWithLifecycle()
    val isAppMapped = vdTargetPackage != null && !launching
    // 停止目标应用的确认弹窗
    var showStopConfirm by remember { mutableStateOf(false) }

    // executeMessage 已迁移到全局 Snackbar（MainActivity），此处仅消费掉避免堆积
    LaunchedEffect(executeMessage) {
        executeMessage?.let { _ ->
            vm.consumeExecuteMessage()
        }
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val appInfo = AppManager.getAppByPackageName(context, AppManager.DEFAULT_PACKAGE_TAOBAO)
            if (appInfo != null) {
                taobaoIcon = appInfo.icon
            }
        }
    }

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(3f)
                ) {
                    VirtualDisplayPreview(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
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
                        isAppMapped = isAppMapped,
                        isSurfaceAvailable = isSurfaceAvailable,
                        displaySize = displaySize,
                        onClick = { if (isAppMapped) isFullscreen = true }
                    ) {
                        previewContent()
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(7f)
                ) {
                    AppLauncherRow(
                        appIcon = taobaoIcon,
                        launching = launching,
                        isMapped = isAppMapped,
                        onLaunchClick = {
                            if (launching) return@AppLauncherRow
                            launching = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val (ok, msg) = vm.launchAppWithOrientationAdaptation(
                                        context = context,
                                        packageName = AppManager.DEFAULT_PACKAGE_TAOBAO
                                    )
                                    if (!ok) {
                                        android.util.Log.w(
                                            "TasksScreen",
                                            "Launch target app failed: $msg"
                                        )
                                    }
                                } catch (e: Exception) {
                                    val crashText = "启动时出现异常：${e::class.java.simpleName} - " +
                                            (e.message ?: e.stackTraceToString().take(200))
                                    android.util.Log.e("TasksScreen", "launch crash", e)
                                    vm.reportLauncherCrash(crashText + "\n" +
                                            android.util.Log.getStackTraceString(e))
                                    vm.pushExecuteMessage("启动异常（详情见『日志』）：" +
                                            (e.message ?: e.javaClass.simpleName))
                                } finally {
                                    launching = false
                                }
                            }
                        },
                        onStopClick = { showStopConfirm = true }
                    )

                    // 停止目标应用的确认弹窗
                    if (showStopConfirm) {
                        AlertDialog(
                            onDismissRequest = { showStopConfirm = false },
                            title = {
                                Text(
                                    text = "停止目标应用",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            text = {
                                Text(
                                    text = "确定要关闭已映射到虚拟显示器的应用吗？\n这将强制结束目标应用进程，映射关系将解除。",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showStopConfirm = false
                                        vm.stopTargetApp()
                                    }
                                ) {
                                    Text(
                                        text = "确定关闭",
                                        color = Color(0xFFFF3B30)
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showStopConfirm = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    TasksTabsSection(
                        vm = vm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .widthIn(max = 520.dp)
                            .weight(1f)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        } else {
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

/** 日志 Tab 中的浮动停止按钮：isExecuting 且当前在日志 Tab 时显示，覆盖在内容之上 */
@Composable
private fun FloatingStopButton(
    visible: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        label = "fabScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "fabAlpha"
    )

    if (scale > 0.01f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 20.dp, bottom = 24.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(alpha)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B30))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White)
                )
            }
        }
    }
}

/** */
@Composable
private fun VirtualDisplayPreview(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isAppMapped: Boolean,
    isSurfaceAvailable: Boolean,
    displaySize: Pair<Int, Int>,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val (bufferWidth, bufferHeight) = displaySize
    val aspectRatio = if (bufferWidth > 0 && bufferHeight > 0) {
        bufferWidth.toFloat() / bufferHeight.toFloat()
    } else {
        16f / 9f
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
                .height(cardHeight),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                if (isRunning && !isSurfaceAvailable) {
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

                // ★ 透明可点击覆盖层：在 z-order 上高于 SurfaceView 和遮罩，
                // 直接捕获点击事件，避免 AndroidView(SurfaceView) 拦截导致 Card.clickable 失效
                // 仅在目标应用已映射到 VD 时才可点击进入全屏（未映射时点击无意义）
                if (isAppMapped) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onClick)
                    )
                }

                // ★状态标签只保留两态：运行中 / 未启动
                val (dotColor, label) = if (isAppMapped && isSurfaceAvailable) {
                    Color(0xFF4CAF50) to "运行中"
                } else {
                    Color(0xFF9E9E9E) to "未启动"
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

/** */
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

    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    BackHandler(enabled = true) {
        if (isRunning) vm.onBackPress() else onExit()
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

    // viewToVirtualDisplay 接收的 viewWidth/viewHeight 与 VD 比例一致，
    // 黑边偏移自然为 0，触摸点不会因误判黑边而被丢弃。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val aspectRatio = if (bufferWidth > 0 && bufferHeight > 0) {
                bufferWidth.toFloat() / bufferHeight.toFloat()
            } else {
                16f / 9f
            }
            val maxWidthPx = maxWidth
            val maxHeightPx = maxHeight
            val widthFromHeight = maxHeightPx * aspectRatio
            val heightFromWidth = maxWidthPx / aspectRatio
            val (previewW, previewH) = if (widthFromHeight <= maxWidthPx) {
                widthFromHeight to maxHeightPx
            } else {
                maxWidthPx to heightFromWidth
            }

            Box(
                modifier = Modifier
                    .width(previewW)
                    .height(previewH)
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
                                    else -> { }
                                }
                            }
                        }
                    }
            ) {
                previewContent()

                IconButton(
                    onClick = onExit,
                    modifier = Modifier
                        // ★以按钮自身右上角为锚点（TopEnd 让按钮右上角对齐 VD 右上角），
                        // 适当增大 padding 让按钮往左下偏移，避免紧贴屏幕圆角/边缘导致显示不全
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "退出全屏",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/** */
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

private fun android.content.Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** */
@Composable
private fun AppLauncherRow(
    appIcon: Drawable?,
    launching: Boolean,
    isMapped: Boolean,
    onLaunchClick: () -> Unit,
    onStopClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.White
        ),
        leadingContent = {
            AppIconDrawable(
                icon = appIcon,
                modifier = Modifier.size(40.dp)
            )
        },
        headlineContent = {
            Text(
                text = "淘宝",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = AppManager.DEFAULT_PACKAGE_TAOBAO,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            // 启动/停止按钮切换：目标应用已映射到 VD 时显示红色停止按钮，否则显示启动按钮
            Button(
                onClick = if (isMapped) onStopClick else onLaunchClick,
                enabled = if (isMapped) true else !launching,
                contentPadding = ButtonDefaults.ContentPadding,
                colors = ButtonDefaults.buttonColors(
                    // ★停止按钮红色（与 FloatingStopButton / 执行按钮的红色保持一致）
                    containerColor = if (isMapped) Color(0xFFFF3B30)
                                     else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(40.dp)
            ) {
                if (isMapped) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = "停止")
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (launching) "启动中" else "启动"
                    )
                }
            }
        }
    )
}

/**
 * Drawable 图标 -> AndroidView(ImageView)
 *
 * AdaptiveIconDrawable（如淘宝 icon）默认会铺满整个方形 ImageView，导致 background 层
 * 填充到四角（看起来像"灰色尖角"）。用 clipToOutline + 圆形 ViewOutlineProvider 把方形
 * 四角剪掉，只显示圆形内的内容，与 M3 ListItem leadingContent 视觉规范一致。
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
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
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

/** */
@Composable
private fun TasksTabsSection(
    vm: MonitorViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val isExecuting by vm.isExecuting.collectAsStateWithLifecycle()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("任务", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("日志", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                    )
                }

                when (selectedTab) {
                    0 -> TasksTabContent(vm)
                    1 -> LogsTabContent(vm)
                }
            }

            FloatingStopButton(
                visible = isExecuting && selectedTab == 1,
                onClick = { vm.stopExecuting() }
            )
        }
    }
}

/** */
@Composable
private fun TasksTabContent(vm: MonitorViewModel) {
    val isExecuting by vm.isExecuting.collectAsStateWithLifecycle()
    val taskFiles by vm.taskFiles.collectAsStateWithLifecycle()
    val selectedTaskFileId by vm.selectedTaskFileId.collectAsStateWithLifecycle()

    val contentHPadding = 20.dp

    LaunchedEffect(Unit) {
        vm.refreshTaskFiles()
    }

    var dropdownExpanded by remember { mutableStateOf(false) }
    val selectedTaskFile = taskFiles.firstOrNull { it.id == selectedTaskFileId }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        val borderColor = if (dropdownExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
        val borderWidth = if (dropdownExpanded) 2.dp else 1.dp
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHPadding, vertical = 20.dp)
        ) {
            val dropdownWidth = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { dropdownExpanded = !dropdownExpanded }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedTaskFile?.name ?: "请选择",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selectedTaskFile != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            androidx.compose.material3.DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier
                    .width(dropdownWidth)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (taskFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "暂无任务文件（放入 filesDir/tasks/）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    taskFiles.forEach { task ->
                        val isSelected = task.id == selectedTaskFileId
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = task.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Spacer(Modifier.width(26.dp))
                                        Text(
                                            text = task.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            },
                            onClick = {
                                vm.selectTaskFile(task.id)
                                dropdownExpanded = false
                            },
                            modifier = Modifier.height(44.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        val morphProgress by animateFloatAsState(
            targetValue = if (isExecuting) 1f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "morphProgress"
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHPadding)
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxButtonWidth = maxWidth
            val widthProgress = (morphProgress / 0.6f).coerceIn(0f, 1f)
            val offsetProgress = ((morphProgress - 0.4f) / 0.6f).coerceIn(0f, 1f)

            val buttonWidth = maxButtonWidth - (maxButtonWidth - 48.dp) * widthProgress
            val offsetX = ((maxButtonWidth - 48.dp) / 2) * offsetProgress

            val containerColor = if (morphProgress > 0.5f) {
                Color(0xFFFF3B30)
            } else {
                MaterialTheme.colorScheme.primary
            }

            val buttonElevation = if (morphProgress > 0.5f) 8.dp else 0.dp

            Button(
                onClick = { if (isExecuting) vm.stopExecuting() else vm.executeTask() },
                modifier = Modifier
                    .height(48.dp)
                    .width(buttonWidth)
                    .offset(x = offsetX)
                    .shadow(buttonElevation, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(0.dp),
                elevation = ButtonDefaults.buttonElevation(
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                    defaultElevation = 0.dp
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (morphProgress < 0.5f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("执行任务")
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color.White)
                        )
                    }
                }
            }
        }

        }

    }
}

/** */
@Composable
private fun LogsTabContent(vm: MonitorViewModel) {
    val logs by vm.scriptLogs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    fun copyAllLogs() {
        val text = logs.joinToString("\n")
        if (text.isBlank()) {
            vm.showSnack("无日志可复制")
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AutoBOT 执行日志", text))
        vm.showSnack("已复制 ${logs.size} 行日志到剪贴板")
    }

    fun exportLogs() {
        val text = logs.joinToString("\n")
        if (text.isBlank()) {
            vm.showSnack("无日志可导出")
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date())
        val fileName = "Auto_$timestamp.txt"
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val outFile = java.io.File(downloadsDir, fileName)
            outFile.writeText(text, Charsets.UTF_8)
            vm.showSnack("已导出到 Download/$fileName")
        } catch (e: Exception) {
            vm.showSnack("导出失败：${e.message}")
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val contentHPadding = 20.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "执行日志",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (logs.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${logs.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (logs.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { copyAllLogs() },
                enabled = logs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("复制", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { exportLogs() },
                enabled = logs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_file_download),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("导出", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { vm.clearLogs() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("清空", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = contentHPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "暂无日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "执行任务后日志将显示在此",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = contentHPadding, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = logLineColor(line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** */
@Composable
private fun logLineColor(line: String): Color {
    return when {
        line.contains("✓") || line.contains("✅") -> Color(0xFF4CAF50)
        line.contains("❌") || line.contains("✗") -> MaterialTheme.colorScheme.error
        line.contains("🚀") || line.contains("⟶") -> MaterialTheme.colorScheme.primary
        line.contains("⏹") -> Color(0xFFFF9800)
        line.contains("🔄") || line.startsWith("━") -> MaterialTheme.colorScheme.primary
        line.contains("⏳") -> MaterialTheme.colorScheme.onSurfaceVariant
        line.startsWith("║") || line.startsWith("╔") || line.startsWith("╚") ->
            Color(0xFF2B6BCA)
        else -> MaterialTheme.colorScheme.onSurface
    }
}
