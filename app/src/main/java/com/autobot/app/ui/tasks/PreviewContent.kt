package com.autobot.app.ui.tasks

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** */
@Composable
fun PreviewContent(
    vm: MonitorViewModel,
    isFullscreen: Boolean,
    onSurfaceAvailable: () -> Unit = {},
    onSurfaceDestroyed: () -> Unit = {},
    /**
     * 原生 SurfaceView 触摸回调（绕过 Compose 的 pointerInput，
     * 避免 Android 15+ 下 SurfaceView 抢事件导致 Compose 覆盖层收不到触摸）。
     * actionMasked: MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP / ACTION_CANCEL
     * viewWidth / viewHeight: 当前 SurfaceView 自身的像素尺寸，用于调用方做坐标映射
     */
    onPreviewTouch: (viewX: Int, viewY: Int, viewWidth: Int, viewHeight: Int, actionMasked: Int) -> Unit =
        { _, _, _, _, _ -> }
) {
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()
    // VD 停止后清空 Surface 最后一帧的信号（自增计数）
    val clearPreviewTick by vm.clearPreviewTick.collectAsStateWithLifecycle()

    var lastSentSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    var lastFixedSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // 持有 SurfaceView 引用，供清屏信号触发时重绘空白底色
    val surfaceViewRef = remember { mutableStateOf<SurfaceView?>(null) }

    val (bufferWidth, bufferHeight) = displaySize

    // ★VD 停止后 SurfaceView 仍保留最后一帧画面，
    //  监听 clearPreviewTick 递增时用 lockCanvas 重绘空白底色覆盖残留帧
    LaunchedEffect(clearPreviewTick) {
        if (clearPreviewTick <= 0) return@LaunchedEffect
        val view = surfaceViewRef.value ?: return@LaunchedEffect
        val holder = view.holder
        val surface = holder.surface
        if (!surface.isValid) return@LaunchedEffect
        try {
            val canvas: Canvas = holder.lockCanvas()
            canvas.drawColor(0xFFE8E4DE.toInt())
            holder.unlockCanvasAndPost(canvas)
        } catch (_: Exception) { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    surfaceViewRef.value = this
                    holder.setFormat(PixelFormat.RGBA_8888)

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // 必须先 setFixedSize 才能正确显示
                            holder.setFixedSize(bufferWidth, bufferHeight)
                            lastFixedSize = bufferWidth to bufferHeight

                            // 不能用 setBackgroundColor() —— 它会持续重绘覆盖 VD 帧
                            try {
                                val canvas: Canvas = holder.lockCanvas()
                                canvas.drawColor(0xFFE8E4DE.toInt())
                                holder.unlockCanvasAndPost(canvas)
                            } catch (_: Exception) { }

                            onSurfaceAvailable()
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            if (width <= 0 || height <= 0) return

                            val newSurface = holder.surface
                            if (newSurface !== lastSentSurface && newSurface.isValid) {
                                lastSentSurface = newSurface
                                vm.onPreviewSurfaceReady(newSurface)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            lastSentSurface = null
                            lastFixedSize = null
                            onSurfaceDestroyed()
                            vm.onPreviewSurfaceDestroyed()
                        }
                    })

                    // 之前设置空 OnClickListener 会消费点击事件，导致全屏切换失效
                    setOnClickListener(null)
                }
            },
            update = { view: SurfaceView ->
                view.setOnClickListener(null)

                // ★ 直接在 SurfaceView 上挂原生 OnTouchListener：
                // Android 15+ 下 SurfaceView 的触摸派发优先级高于 Compose 的 pointerInput，
                // 用覆盖层仍然收不到事件，只能直接在 SurfaceView 上拦截。
                // 放在 update 里而不是 factory 里，保证 isFullscreen / onPreviewTouch
                // 变化时（全屏切换）能刷新最新闭包（factory 只在首次创建时跑一次）。
                // isFullscreen 才拦截触摸，非全屏时交给上层透明 clickable Box 处理（进入全屏）
                view.setOnTouchListener { v, event ->
                    if (!isFullscreen) return@setOnTouchListener false
                    val action = event.actionMasked
                    if (action == MotionEvent.ACTION_DOWN ||
                        action == MotionEvent.ACTION_MOVE ||
                        action == MotionEvent.ACTION_UP ||
                        action == MotionEvent.ACTION_CANCEL
                    ) {
                        onPreviewTouch(
                            event.x.toInt(),
                            event.y.toInt(),
                            v.width,
                            v.height,
                            action
                        )
                        true
                    } else {
                        false
                    }
                }

                val currentSize = lastFixedSize
                val desiredW = bufferWidth
                val desiredH = bufferHeight
                if ((currentSize == null ||
                            currentSize.first != desiredW ||
                            currentSize.second != desiredH) &&
                    desiredW > 0 && desiredH > 0
                ) {
                    try {
                        view.holder.setFixedSize(desiredW, desiredH)
                        lastFixedSize = desiredW to desiredH
                    } catch (e: Exception) {
                        // Surface 尚未创建或已销毁时 setFixedSize 可能抛 IllegalStateException
                    }
                }
            }
        )
    }
}
