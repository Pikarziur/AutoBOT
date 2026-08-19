package com.autobot.app.ui.tasks

import android.app.Activity
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()
    val executeMessage by vm.executeMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isFullscreen by remember { mutableStateOf(false) }
    val isLandscape by vm.isLandscape.collectAsStateWithLifecycle()

    // 同步本地 isFullscreen 状态到 ViewModel，供 Activity 观察后隐藏底部导航栏
    LaunchedEffect(isFullscreen) {
        vm.setFullscreen(isFullscreen)
    }

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

    // 初次进入：仅加载淘宝图标（不自动启动虚拟显示器）
    // VD 由用户点击播放按钮时通过 launchAppWithOrientationAdaptation 重建并启动淘宝
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val appInfo = AppManager.getAppByPackageName(context, AppManager.DEFAULT_PACKAGE_TAOBAO)
            if (appInfo != null) {
                taobaoIcon = appInfo.icon
            }
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
                        onClick = { if (isRunning) isFullscreen = true }
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

                    // 视觉分隔：启动条 vs 任务日志区 - 两个独立组件，留出空白间距
                    Spacer(modifier = Modifier.height(8.dp))

                    // 任务/日志 选项卡区域（淘宝启动条下方）
                    // weight(1f)：占满 70% 业务区扣除启动条之后的剩余高度
                    // start/end/bottom padding 让 Card 与屏幕边缘留出间距，强化"独立卡片"视觉感
                    // （不能用 horizontal + bottom 混搭，Compose padding 无此重载）
                    TasksTabsSection(
                        vm = vm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
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
                .height(cardHeight),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                // 未启动时不显示遮罩文字（全黑背景 + 右上角"未启动"指示器已足够）
                // 仅在已启动但 Surface 尚未绑定时提示
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
                // 未启动时不消费点击事件（不可进入全屏）
                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onClick)
                    )
                }

                // 右上角状态指示器（在最上层，不消费点击事件）
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
 * - 边缘滑动/物理返回键：VD 运行时注入 KEYCODE_BACK 让目标 App 返回上一层；未运行时退出全屏
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

    // VD 运行时：边缘滑动/物理返回键 → 注入 KEYCODE_BACK 到 VD，让目标 App 返回上一层
    // VD 未运行时：退出全屏
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

    // ============ 等比缩放 + 黑边（对齐 MAA-Meow 全屏实现） ============
    // 外层：屏幕黑底（黑边来源）
    // 中层：BoxWithConstraints 等比计算预览区域尺寸并居中
    // 内层：实际显示区域，SurfaceView + 触摸事件 + 退出按钮都在这里
    //       这样 viewToVirtualDisplay 接收的 viewWidth/viewHeight 与 VD 比例一致，
    //       黑边偏移自然为 0，触摸点不会因误判黑边而被丢弃。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 与小窗模式 VirtualDisplayPreview 完全一致的等比缩放算法
            val aspectRatio = if (bufferWidth > 0 && bufferHeight > 0) {
                bufferWidth.toFloat() / bufferHeight.toFloat()
            } else {
                16f / 9f  // 兜底
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

            // 实际显示区域：触摸事件在这里处理，保证触摸坐标与画面 1:1 对应
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
                                    else -> { /* ignore */ }
                                }
                            }
                        }
                    }
            ) {
                // SurfaceView 填满实际显示区域
                previewContent()

                // 退出按钮放在画面右上角（黑边内），避免遮挡到屏幕边缘
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
 * 淘宝启动条（Material 3 ListItem + FilledButton 重设计）
 *
 * 布局结构（M3 ListItem 三段式）：
 *   ┌──────────────────────────────────────────────────────┐
 *   │ [图标]  淘宝                                [▶ 启动] │
 *   │  40dp   com.taobao.taobao                      40dp  │
 *   │         ↑ headline(titleMedium)                  ↑   │
 *   │         ↑ supportingContent(bodySmall+灰)        ↑   │
 *   │                                                 FilledButton│
 *   └──────────────────────────────────────────────────────┘
 *      ↑ containerColor = surfaceVariant（暖灰底）
 *      ↑ clip(RoundedCornerShape(12dp)) 与项目卡片圆角对齐
 *
 * 与旧版差异：
 *   - 移除自定义 Row + Surface 包装，改用 M3 ListItem 标准三段式
 *   - 启动按钮从 IconButton(圆形 44dp) 升级为 FilledButton(图标+文字, 高度 40dp)
 *   - 新增 supportingContent 显示包名，提供更明确的语义信息
 *   - 启动中状态：按钮文字变为 "启动中"，按钮 enabled=false 自动灰显
 */
@Composable
private fun TaobaoLauncherRow(
    taobaoIcon: Drawable?,
    launching: Boolean,
    onLaunchClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.White  // 纯白底 #FFFFFF（覆盖 surfaceVariant #E8E4DE）
        ),
        leadingContent = {
            AppIconDrawable(
                icon = taobaoIcon,
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
            Button(
                onClick = onLaunchClick,
                enabled = !launching,
                contentPadding = ButtonDefaults.ContentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.height(40.dp)
            ) {
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
                // 圆形剪裁：把方形 ImageView 四角剪掉，避免 AdaptiveIcon 的 background 层显示在尖角
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

// ============================================================================
// 任务 / 日志 选项卡区域（淘宝启动条下方）
// ============================================================================

/**
 * 任务/日志 选项卡区域
 *
 * 整体包裹在白色 Card 内，形成一个独立的"组件"视觉单元：
 *   - Card：白底 + 12dp 圆角 + 1dp 阴影（与项目其他卡片样式一致）
 *   - TabRow(M3) + 内容区：
 *     - Tab 0 "任务"：模式切换 + 模式对应内容（SH-ADB / 截图识别占位）
 *     - Tab 1 "日志"：实时显示脚本执行日志
 *
 * 选中状态由本组件 remember 持有，无需提升到 ViewModel（与 VM 业务状态无关）
 */
@Composable
private fun TasksTabsSection(
    vm: MonitorViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }  // 0=任务, 1=日志

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
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
                    text = { Text("任务", style = MaterialTheme.typography.labelLarge) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("日志", style = MaterialTheme.typography.labelLarge) }
                )
            }

            when (selectedTab) {
                0 -> TasksTabContent(vm)
                1 -> LogsTabContent(vm)
            }
        }
    }

    // .sh 文件选择器 Dialog（Shizuku find/cat 路径）
    // 挂在 TasksTabsSection 末尾，跨 Tab/模式切换均可见，由 vm.shFilePickerVisible 控制
    ShFilePickerDialog(vm)
}

/**
 * 任务标签页
 *
 * 布局自上而下：
 *   1. 模式选择（竖向 RadioButton + 文本，单选交互）
 *      - adb shell
 *      - [SH-ADB 下拉框 + 添加/删除按钮]（仅 SH-ADB 模式下显示，夹在两个 RadioButton 之间）
 *      - 截图识别
 *   2. 模式对应内容区（weight(1f) 占满中部空间）
 *      - SH-ADB：无额外内容（下拉框已移至 RadioButton 之间）
 *      - 截图识别：占位文本
 *   3. 底部执行任务按钮（跨模式，根据当前模式启用/禁用）
 *
 * RadioButton 行为：
 *   - 选中：实心 primary + onSurface 主色文字
 *   - 未选：描边 + onSurfaceVariant 灰文字
 *   - 整行可点击（点 Row 任意位置即触发 onClick，等同于点 RadioButton）
 */
@Composable
private fun TasksTabContent(vm: MonitorViewModel) {
    val selectedMode by vm.selectedMode.collectAsStateWithLifecycle()
    val selectedId by vm.selectedScriptTaskId.collectAsStateWithLifecycle()
    val isExecuting by vm.isExecuting.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 1. 模式选择（竖向 RadioButton） ----------
        // 布局：adb shell → [SH-ADB 下拉框+按钮] → 截图识别
        // 下拉框+按钮夹在两个 RadioButton 之间，视觉上归属 adb shell 模式
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeRadioButtonRow(
                label = "adb shell",
                selected = selectedMode == MonitorViewModel.TaskMode.SH_ADB,
                onClick = { vm.selectMode(MonitorViewModel.TaskMode.SH_ADB) }
            )

            // SH-ADB 模式下：在 adb shell 与 截图识别 之间渲染脚本下拉框 + 添加/删除按钮
            if (selectedMode == MonitorViewModel.TaskMode.SH_ADB) {
                ShAdbModeContent(vm)
            }

            ModeRadioButtonRow(
                label = "截图识别",
                selected = selectedMode == MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION,
                onClick = { vm.selectMode(MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION) }
            )
        }

        // ---------- 2. 模式对应内容区 ----------
        // SH-ADB：下拉框已移至 RadioButton 之间，此处无额外内容（保留弹性空间）
        // 截图识别：占位文本
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedMode == MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION) {
                // 截图识别模式占位（后续版本完善）
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "截图识别模式（功能开发中）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "后续版本将完善此功能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---------- 3. 底部执行任务按钮 ----------
        // SH-ADB：选中任务且未执行时可点击
        // 截图识别：禁用（功能开发中）
        val canExecute = when (selectedMode) {
            MonitorViewModel.TaskMode.SH_ADB -> selectedId != null && !isExecuting
            MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION -> false
        }
        val executeLabel = when (selectedMode) {
            MonitorViewModel.TaskMode.SH_ADB -> if (isExecuting) "执行中..." else "执行任务"
            MonitorViewModel.TaskMode.SCREENSHOT_RECOGNITION -> "功能开发中"
        }

        Button(
            onClick = { vm.executeTask() },
            enabled = canExecute,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(executeLabel)
        }
    }
}

/**
 * 模式单选项（RadioButton + 文本，竖向排列用）
 *
 * - 整行可点击：点 Row 任意位置均触发 onClick（等同点 RadioButton 本身）
 * - selected=true ：RadioButton 选中 + 文字主色 onSurface
 * - selected=false：RadioButton 未选 + 文字灰 onSurfaceVariant
 * 始终可点击，单选交互（点未选项即切换到该模式）
 */
@Composable
private fun ModeRadioButtonRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledSelectedColor = MaterialTheme.colorScheme.primary,
                disabledUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * SH-ADB 模式内容
 *
 * 单行布局：[脚本下拉框] [+] [−]
 *   - 下拉框：显示当前选中的脚本任务名（或"请选择脚本"占位）
 *            点击展开 DropdownMenu，列出所有 scriptTasks 供选择
 *            空列表时显示"暂无脚本，请点击 + 添加"
 *   - + 按钮：SAF OpenDocument 选取 .sh 文件 → vm.importScript
 *   - − 按钮：删除当前选中任务（无选中时禁用）
 *
 * 注：执行按钮位于父级 TasksTabContent 底部（跨模式），本组件不渲染执行入口
 *
 * 脚本文件存储路径：app 内部 filesDir/Mode1_SH/<uuid>_<原文件名>.sh
 *
 * 下拉框视觉优化（与应用整体设计风格一致）：
 *   - 圆角 12dp（与项目卡片圆角对齐）
 *   - 边框：默认 1dp outline 灰；展开时 2dp primary 蓝（视觉反馈）
 *   - 背景：surface 白；高度 44dp（标准触摸目标）
 *   - 文字：选中 titleSmall 主色；占位 bodyMedium 灰
 *   - 下拉箭头：默认灰；展开时 primary 蓝（与边框协调）
 *   - 列表项：选中项左侧 Check 图标 + primary 文字 + Medium 字重
 *
 * 实现说明：
 *   - 不使用 ExposedDropdownMenuBox（material3 1.1.x 为 @ExperimentalMaterial3Api）
 *   - 用 Box + Modifier.border + DropdownMenu 等价实现，避免实验性 API
 */
@Composable
private fun ShAdbModeContent(vm: MonitorViewModel) {
    val scriptTasks by vm.scriptTasks.collectAsStateWithLifecycle()
    val selectedId by vm.selectedScriptTaskId.collectAsStateWithLifecycle()

    // 注：原 SAF 弹窗被国内 ROM "安全浏览" 拦截 .sh 文件，
    //     改用 Shizuku `find` + `cat` 路径绕开系统过滤层（详见 vm.showShFilePicker）

    // 下拉框展开状态（remember 持有，无需提升到 VM）
    var expanded by remember { mutableStateOf(false) }
    val selectedTask = scriptTasks.find { it.id == selectedId }

    // 边框/箭头颜色：展开时 primary 蓝（视觉反馈），否则 outline 灰
    val borderColor = if (expanded) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val borderWidth = if (expanded) 2.dp else 1.dp
    val arrowColor = if (expanded) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---------- 脚本下拉框（占满剩余宽度） ----------
        Box(modifier = Modifier.weight(1f)) {
            // 输入框样式：圆角 12dp + 描边 + 文本 + 下拉箭头
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)  // 标准触摸目标高度
                    .clip(RoundedCornerShape(12.dp))  // 12dp 与项目卡片圆角一致
                    .background(MaterialTheme.colorScheme.surface)  // 白色背景
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedTask?.name ?: "请选择脚本",
                        modifier = Modifier.weight(1f),
                        style = if (selectedTask != null) {
                            MaterialTheme.typography.titleSmall  // 选中时更醒目
                        } else {
                            MaterialTheme.typography.bodyMedium  // 占位时常规
                        },
                        color = if (selectedTask != null) {
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
                        tint = arrowColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 下拉弹出菜单（位置由框架自动计算，默认在锚点 Box 下方）
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (scriptTasks.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "暂无脚本，请点击 + 添加",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { expanded = false }
                    )
                } else {
                    scriptTasks.forEach { task ->
                        val isSelected = task.id == selectedId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = task.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                )
                            },
                            onClick = {
                                vm.selectScriptTask(task.id)
                                expanded = false
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        }

        // ---------- 添加按钮（Shizuku find 列出 .sh 文件，弹 Dialog 选）----------
        IconButton(
            onClick = { vm.showShFilePicker() },
            modifier = Modifier.size(44.dp)  // 与下拉框同高，视觉对齐
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "添加脚本",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        // ---------- 删除按钮（仅当有选中任务时启用） ----------
        IconButton(
            onClick = { selectedId?.let { vm.deleteScriptTask(it) } },
            enabled = selectedId != null,
            modifier = Modifier.size(44.dp)  // 与下拉框同高
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除脚本",
                tint = if (selectedId != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * .sh 文件选择器 Dialog（Shizuku find/cat 路径，绕开 SAF）
 *
 * 调用方：[ShAdbModeContent] 的 + 按钮触发 `vm.showShFilePicker()` → 本 Dialog 出现
 *
 * 三种状态：
 *   - 加载中（vm.isListingShFiles）：CircularProgressIndicator + "扫描 .sh 文件中..."
 *   - 空列表：空状态文本"未找到 .sh 文件，请先 push 到 /sdcard/"
 *   - 列表非空：LazyColumn 列出所有路径，点击触发 vm.importScriptFromPath(path)
 *
 * 列表项布局：上方文件名（高亮主色 + Medium 字重）+ 下方完整路径（灰色辅助）
 *
 * 注：Dialog 渲染在独立 window，挂在 [TasksTabsSection] 末尾，跨 Tab/模式切换均可见
 */
@Composable
private fun ShFilePickerDialog(vm: MonitorViewModel) {
    val visible by vm.shFilePickerVisible.collectAsStateWithLifecycle()
    val isListing by vm.isListingShFiles.collectAsStateWithLifecycle()
    val fileList by vm.shFileList.collectAsStateWithLifecycle()

    if (!visible) return

    AlertDialog(
        onDismissRequest = { vm.hideShFilePicker() },
        title = { Text("选择 .sh 文件") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                when {
                    // 1. 加载中：find 命令在 IO 线程执行
                    isListing -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "扫描 .sh 文件中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // 2. 空列表：find 命令没找到任何 .sh 文件
                    fileList.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "未找到 .sh 文件\n请先 push 到 /sdcard/",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    // 3. 列表非空：渲染可点击路径列表
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(fileList) { path ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.importScriptFromPath(path) }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        // 上：文件名（去掉路径），高亮主色
                                        Text(
                                            text = path.substringAfterLast('/'),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        // 下：完整路径，灰色辅助
                                        Text(
                                            text = path,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.hideShFilePicker() }) {
                Text("取消")
            }
        }
    )
}

/**
 * 日志标签页
 *
 * 实时显示脚本执行日志（vm.scriptLogs，仅 taskListener 写入）
 * 日志行格式（由 MonitorViewModel 拼装，已包含时间戳/任务名/状态/详情）：
 *   [HH:mm:ss] [类型标记] 内容
 *   类型标记：⟶ 启动 / ✓ 完成 / ✗ 出错 / ⏹ 停止 / [id] 输出
 *
 * 仅脚本执行相关日志：
 *   - 启动/输出/完成/停止/出错 等任务生命周期事件
 *   - 不包含 Shizuku 诊断 / VD 启动 等非脚本日志（这些走 [_taskLogs] 流，UI 不展示）
 *
 * 特性：
 *   - LazyColumn 逐行渲染，新日志到达后自动滚动到底
 *   - 右上角「清空」按钮调用 vm.clearLogs()
 *   - 不同状态用不同颜色区分（绿/红/蓝/橙/黑）
 *   - 最多 500 行（由 VM 限制），避免内存无限增长
 */
@Composable
private fun LogsTabContent(vm: MonitorViewModel) {
    val logs by vm.scriptLogs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 日志列表新增 → 自动滚到底部（最新）
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 头部：标题 + 清空按钮 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "执行日志（${logs.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.clearLogs() }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("清空")
            }
        }

        // ---------- 日志列表 ----------
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = logLineColor(line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * 日志行颜色：根据内容标记区分状态
 * - ✓ 完成 → 绿色
 * - ✗ 出错 → 红色
 * - ⟶ 启动 → 蓝色
 * - ⏹ 停止 → 橙色
 * - 其他 → 主文字色
 */
@Composable
private fun logLineColor(line: String): Color {
    return when {
        line.contains("✓") -> Color(0xFF4CAF50)
        line.contains("✗") -> MaterialTheme.colorScheme.error
        line.contains("⟶") -> MaterialTheme.colorScheme.primary
        line.contains("⏹") -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.onSurface
    }
}
