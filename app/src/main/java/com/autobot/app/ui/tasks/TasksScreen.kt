package com.autobot.app.ui.tasks

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
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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

    // executeMessage 变化时弹 Toast：
    //   - 完整错误/详情已经被写入"日志 Tab"（MonitorViewModel.appendLog）
    //   - Toast 仅作提示：过长内容自动截断到 48 字内 + 引导用户切到『日志』查看完整原因
    //   - 长度 < 48：直接显示（完整）
    fun buildToastText(full: String): String {
        val trimmed = full.trim()
        return if (trimmed.length <= 48) trimmed
        else "${trimmed.take(44)}…\n（详情切到『日志』查看，可复制）"
    }
    LaunchedEffect(executeMessage) {
        executeMessage?.let { msg ->
            Toast.makeText(
                context,
                buildToastText(msg),
                Toast.LENGTH_LONG
            ).show()
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
                                    val (ok, msg) = vm.launchAppWithOrientationAdaptation(
                                        context = context,
                                        packageName = AppManager.DEFAULT_PACKAGE_TAOBAO
                                    )
                                    // ⚠️ 注意：
                                    //  - 完整 msg（Shizuku 诊断/任务隔离详情/dumpsys 输出等）已经由 VM
                                    //    通过 appendLog(...) 写入『日志 Tab』，
                                    //    用户可以在日志里滚动查看 + 用『复制』按钮拷贝完整文本。
                                    //  - UI 顶部的 Toast 只显示前 48 字的简短版，
                                    //    过长时会自动提示 "详情切到『日志』查看"。
                                    //  - 这里只处理崩溃型异常（Exception），返回值走 executeMessage。
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
                                    // 把崩溃堆栈也写到『日志 Tab』，方便直接复制
                                    vm.reportLauncherCrash(crashText + "\n" +
                                            android.util.Log.getStackTraceString(e))
                                    // 用短 Toast 提醒，避免顶栏被超长消息压变形
                                    vm.pushExecuteMessage("启动异常（详情见『日志』）：" +
                                            (e.message ?: e.javaClass.simpleName))
                                } finally {
                                    launching = false
                                }
                            }
                        }
                    )

                    // 视觉分隔：启动条 vs 任务日志区 - 加大间距形成"呼吸感"
                    // （两个是独立的白色组件，靠得太近会显得"粘"在一起）
                    Spacer(modifier = Modifier.height(24.dp))

                    // 任务/日志 选项卡区域（淘宝启动条下方）
                    // weight(1f)：占满 70% 业务区扣除启动条 + 24dp 间距之后的剩余高度
                    // widthIn(max=520dp)：大屏/横屏下限制最大宽度，让卡片不显得过长
                    // wrapContentWidth + start/end padding：与启动条视觉对齐且居中留白
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

        // ★ 浮动停止按钮：任务执行时悬浮在页面最上层（仅小窗模式）
        // 红色圆形 + 白色正方形 + 投影，出现在右下角；点击停止任务后恢复原样
        FloatingStopButton(
            visible = isExecuting && !isFullscreen,
            onClick = { vm.stopExecuting() }
        )
    }
}

/**
 * 浮动停止按钮（Floating Action Button 风格）
 *
 * 任务执行时从屏幕右下角浮现的红色圆形按钮：
 *   - 红色圆形背景（56dp，#FF3B30）
 *   - 中间白色正方形（20dp，代表 Stop 符号）
 *   - 带 8dp 投影形成悬浮感，z-order 高于页面所有内容
 *   - 出现/消失动画：scale + alpha 双向动画
 *
 * 触发条件：isExecuting=true 且非全屏模式
 * 消失条件：任务完成 / 任务出错 / 用户点击停止 / 进入全屏
 */
@Composable
private fun FloatingStopButton(
    visible: Boolean,
    onClick: () -> Unit
) {
    // 出现动画：scale 0→1（弹性缩放），alpha 0→1（淡入）
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "fabScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "fabAlpha"
    )

    // scale 降到接近 0 时不再渲染，避免接收点击事件
    if (scale > 0.01f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 20.dp, bottom = 100.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
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
                // 白色正方形（Stop 符号）
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White)
                )
            }
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
 *     - Tab 0 "任务"：任务文件下拉选择 + 形变执行按钮（注入 MotionEvent 到 VD）
 *     - Tab 1 "日志"：实时显示任务执行日志
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

    // 注：任务文件由 TaskFileManager 维护，启动时从 assets/tasks/ 装载到 filesDir/tasks/
    // 用户可在该目录新增/编辑 *.json 任务文件，下次进入任务 Tab 时下拉列表会自动刷新
}

/**
 * 任务标签页
 *
 * 布局自上而下：
 *   1. 任务文件下拉列表（读取 filesDir/tasks/ 下 *.json，由 TaskFileManager 维护）
 *   2. 底部执行任务按钮（形变悬浮按钮：点击后固定右边缘缩放成正圆红色 Stop 浮按钮）
 *
 * 下拉项选中 → vm.selectTaskFile(id)；
 * 点击执行任务 → vm.executeTask()，TaskExecutor 在 App 进程内驱动 MotionEvent 注入到 VD。
 */
@Composable
private fun TasksTabContent(vm: MonitorViewModel) {
    val isExecuting by vm.isExecuting.collectAsStateWithLifecycle()
    val taskFiles by vm.taskFiles.collectAsStateWithLifecycle()
    val selectedTaskFileId by vm.selectedTaskFileId.collectAsStateWithLifecycle()

    // ★统一的"内容水平内边距"：让卡片内容不贴左右边，视觉更精致
    val contentHPadding = 20.dp

    // 切到任务 Tab 时刷新一次任务文件列表（用户外部编辑/新增文件后可见最新）
    LaunchedEffect(Unit) {
        vm.refreshTaskFiles()
    }

    // 下拉展开状态
    var dropdownExpanded by remember { mutableStateOf(false) }
    // 当前选中任务文件对象（null 表示未选中）
    val selectedTaskFile = taskFiles.firstOrNull { it.id == selectedTaskFileId }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 1. 任务下拉列表 ----------
        // 样式与应用整体下拉框一致：12dp 圆角 + 1dp 描边 + 44dp 高 + 白底
        // 展开时描边变为主色蓝（2dp）+ 文字变主色，引导"已激活可选项"
        val borderColor = if (dropdownExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
        val borderWidth = if (dropdownExpanded) 2.dp else 1.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHPadding, vertical = 12.dp)
        ) {
            // 触发器行：点击展开/收起 DropdownMenu
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

            // 下拉菜单：12dp 圆角 + 白底 + 44dp 行高
            // 选中项左侧显示 Check 图标 + 主色文字，未选中项仅文字
            androidx.compose.material3.DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (taskFiles.isEmpty()) {
                    // 没有任何任务文件时显示提示行（不可点击）
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
                                        Spacer(Modifier.width(26.dp))  // 与 Check 图标等宽占位
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

        // ---------- 2. 弹性留白（把按钮顶到底部） ----------
        Spacer(modifier = Modifier.weight(1f))

        // ---------- 3. 底部执行任务按钮 ----------
        // 点击「执行任务」后，按钮淡出 + 缩小消失；
        // 浮动停止按钮（FloatingStopButton）在页面最上层右下角浮现接管。
        // 任务结束或点击红色停止按钮 → 浮动按钮消失，此按钮淡入恢复。
        val canExecute = !isExecuting
        val executeLabel = "执行任务"

        // 执行中时按钮淡出 + 缩小（让位给浮动停止按钮）
        val buttonAlpha by animateFloatAsState(
            targetValue = if (isExecuting) 0f else 1f,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "executeButtonAlpha"
        )
        val buttonScale by animateFloatAsState(
            targetValue = if (isExecuting) 0.3f else 1f,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "executeButtonScale"
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHPadding, vertical = 12.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Button(
                onClick = { vm.executeTask() },
                enabled = canExecute,
                modifier = Modifier
                    .height(48.dp)
                    .widthIn(max = 220.dp)
                    .fillMaxWidth()
                    .alpha(buttonAlpha)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
    }
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
 *   - 头部计数徽章 + 图标按钮（复制/清空）
 *   - 日志使用 Monospace 字体，对齐 emoji 前缀更整齐
 *   - 不同状态用不同颜色 + emoji 区分（绿/红/蓝/橙/黑）
 *   - 最多 500 行（由 VM 限制），避免内存无限增长
 */
@Composable
private fun LogsTabContent(vm: MonitorViewModel) {
    val logs by vm.scriptLogs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 复制全部日志到剪贴板
    fun copyAllLogs() {
        val text = logs.joinToString("\n")
        if (text.isBlank()) {
            Toast.makeText(context, "无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AutoBOT 执行日志", text))
        Toast.makeText(context,
            "已复制 ${logs.size} 行日志到剪贴板",
            Toast.LENGTH_SHORT).show()
    }

    // 日志列表新增 → 自动滚到底部（最新）
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ★统一水平 padding，与任务 Tab 保持一致，不让内容顶到卡片边
        val contentHPadding = 20.dp

        // ---------- 头部：标题 + 计数徽章 + [复制][清空] ----------
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
            // 计数徽章：圆形灰底 + 数字，实时反映日志条数
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
            // ★复制按钮：靠着「清空」按钮的左侧，复制完整日志（空列表时禁用）
            TextButton(
                onClick = { copyAllLogs() },
                enabled = logs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("复制", style = MaterialTheme.typography.labelMedium)
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

        // ---------- 日志列表 ----------
        if (logs.isEmpty()) {
            // 美化空状态：图标 + 提示文字 + 引导副文
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
                        imageVector = Icons.Filled.ContentCopy,
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
                        // ★Monospace 字体：让 [01/60] 序号、坐标、✓ 完成行对齐更整齐
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

/**
 * 日志行颜色：根据内容标记区分状态
 * - ✓ 完成 / ✅ 全部完成 → 绿色
 * - ❌ 出错 → 红色
 * - 🚀 启动 / ⟶ 启动 → 蓝色
 * - ⏹ 停止 → 橙色
 * - 🔄 分组 / ━━━ 分隔线 → 主色
 * - ⏳ 等待中 → 灰色辅助
 * - 其他 → 主文字色
 */
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
            Color(0xFF2B6BCA)  // 启动横幅用主色
        else -> MaterialTheme.colorScheme.onSurface
    }
}
