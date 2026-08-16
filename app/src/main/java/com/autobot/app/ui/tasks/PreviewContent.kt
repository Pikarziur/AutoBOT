package com.autobot.app.ui.tasks

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 可复用的预览内容
 *
 * 使用 movableContentOf 包装，确保在小窗模式与全屏模式之间切换时
 * 复用同一份 SurfaceView 实例和 Surface，避免重复创建/销毁
 *
 * 关键点：
 * 1. AndroidView 包裹原生 SurfaceView
 * 2. factory 中将 holder 格式设为 RGBA_8888，添加 SurfaceHolder.Callback
 * 3. surfaceCreated：通过 holder.setFixedSize 设置 SurfaceView 固定尺寸等于虚拟显示器分辨率
 * 4. surfaceChanged：校验宽高匹配后才将 holder.surface 传给 ViewModel
 *    （用 lastSentSurface 记录避免重复发送）
 * 5. surfaceDestroyed：清空状态并通知 ViewModel 释放 Surface
 * 6. 外层套 aspectRatio 保持虚拟显示器固定宽高比避免画面变形
 * 7. 上层叠加 TouchPreviewOverlay 用于显示点击位置的触摸标记
 */
val PreviewContent = movableContentOf(
    content = { vm: MonitorViewModel, isFullscreen: Boolean, onCloseFullscreen: () -> Unit ->
        PreviewContentImpl(vm, isFullscreen, onCloseFullscreen)
    }
)

@Composable
private fun PreviewContentImpl(
    vm: MonitorViewModel,
    isFullscreen: Boolean,
    onCloseFullscreen: () -> Unit
) {
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()
    val touchMarkers by vm.touchMarkers.collectAsStateWithLifecycle()

    // 记录最近一次传递给 ViewModel 的 Surface，避免重复传递
    var lastSentSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    val (bufferWidth, bufferHeight) = displaySize

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 外层 aspectRatio 保持虚拟显示器固定宽高比，避免画面变形
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(bufferWidth.toFloat() / bufferHeight.toFloat()),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // 设置 holder 格式为 RGBA_8888
                    holder.setFormat(PixelFormat.RGBA_8888)

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // 必须先 setFixedSize 才能正确显示
                            holder.setFixedSize(bufferWidth, bufferHeight)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            // 校验宽高匹配后才将 holder.surface 传给 ViewModel
                            // （宽高与缓冲区一致或通过 setFixedSize 后会回调匹配值）
                            if (width <= 0 || height <= 0) return

                            val newSurface = holder.surface
                            // 用 lastSentSurface 防重复传递
                            if (newSurface !== lastSentSurface && newSurface.isValid) {
                                lastSentSurface = newSurface
                                vm.onPreviewSurfaceReady(newSurface)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // 清空状态并通知 ViewModel 释放 Surface
                            lastSentSurface = null
                            vm.onPreviewSurfaceDestroyed()
                        }
                    })

                    // 小窗模式下点击切换全屏；全屏模式下点击不切换（避免误触）
                    if (!isFullscreen) {
                        setOnClickListener { onCloseFullscreen() }
                    } else {
                        setOnClickListener(null)
                    }
                }
            },
            update = { view: SurfaceView ->
                // 全屏模式下需要更新点击行为
                view.setOnClickListener(if (!isFullscreen) View.OnClickListener {
                    onCloseFullscreen()
                } else null)
            }
        )

        // 上层叠加 TouchPreviewOverlay 用于显示点击位置的触摸标记
        TouchPreviewOverlay(
            markers = touchMarkers,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            modifier = Modifier.fillMaxSize()
        )
    }
}
