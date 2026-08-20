package com.autobot.app.ui.tasks

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
    onSurfaceDestroyed: () -> Unit = {}
) {
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()

    var lastSentSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    var lastFixedSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val (bufferWidth, bufferHeight) = displaySize

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
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
