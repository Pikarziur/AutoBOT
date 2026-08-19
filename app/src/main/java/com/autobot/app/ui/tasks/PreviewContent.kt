package com.autobot.app.ui.tasks

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

/**
 * 可复用的预览内容
 *
 * 使用 movableContentOf 包装，确保在小窗模式与全屏模式之间切换时
 * 复用同一份 SurfaceView 实例和 Surface，避免重复创建/销毁
 *
 * 关键点（照搬 MAA-Meow previewContent）：
 * 1. 外层 Box(fillMaxSize + black)，不再加 aspectRatio —— 宽高比由父容器 VirtualDisplayPreview 的 Card 控制
 * 2. AndroidView 包裹原生 SurfaceView，fillMaxSize 填满 Card
 * 3. holder 格式设为 RGBA_8888，添加 SurfaceHolder.Callback
 * 4. surfaceCreated：通过 holder.setFixedSize 设置 SurfaceView 固定尺寸等于虚拟显示器分辨率
 * 5. surfaceChanged：校验宽高匹配后才将 holder.surface 传给 ViewModel
 *    （用 lastSentSurface 记录避免重复发送）
 * 6. surfaceDestroyed：清空状态并通知 ViewModel 释放 Surface
 * 7. 上层叠加 TouchPreviewOverlay 用于显示点击位置的触摸标记
 */
@Composable
fun PreviewContent(
    vm: MonitorViewModel,
    isFullscreen: Boolean,
    onSurfaceAvailable: () -> Unit = {},
    onSurfaceDestroyed: () -> Unit = {}
) {
    val displaySize by vm.displaySize.collectAsStateWithLifecycle()
    val touchMarkers by vm.touchMarkers.collectAsStateWithLifecycle()

    // 记录最近一次传递给 ViewModel 的 Surface，避免重复传递
    var lastSentSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    // 记录上次应用到 SurfaceView 的固定尺寸（避免重复 setFixedSize）
    var lastFixedSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val (bufferWidth, bufferHeight) = displaySize

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF333333))
    ) {
        // 不再在 AndroidView 上加 aspectRatio —— 外层 VirtualDisplayPreview 的 Card
        // 已经通过 BoxWithConstraints + 宽高比计算限定了尺寸，这里 fillMaxSize 即可
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // SurfaceView 默认背景设为深灰色，未运行时显示灰色而非纯黑
                    setBackgroundColor(0xFF333333)
                    // 设置 holder 格式为 RGBA_8888
                    holder.setFormat(PixelFormat.RGBA_8888)

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // 必须先 setFixedSize 才能正确显示
                            holder.setFixedSize(bufferWidth, bufferHeight)
                            lastFixedSize = bufferWidth to bufferHeight
                            onSurfaceAvailable()
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            // 校验宽高匹配后才将 holder.surface 传给 ViewModel
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
                            lastFixedSize = null
                            onSurfaceDestroyed()
                            vm.onPreviewSurfaceDestroyed()
                        }
                    })

                    // SurfaceView 不设置 OnClickListener，让点击事件透传给父级 Card 的 clickable 处理
                    // 之前设置空 OnClickListener 会消费点击事件，导致全屏切换失效
                    setOnClickListener(null)
                }
            },
            update = { view: SurfaceView ->
                // SurfaceView 始终不消费点击事件，由父级 Card 的 clickable 统一处理全屏切换
                // （小窗模式 → 点击切全屏；全屏模式 → FullscreenMonitor 的 pointerInput 处理触摸注入）
                view.setOnClickListener(null)

                // 2. 切换横竖屏导致 buffer 分辨率变化时，更新 SurfaceView 的固定尺寸
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

        // 上层叠加 TouchPreviewOverlay 用于显示点击位置的触摸标记
        TouchPreviewOverlay(
            markers = touchMarkers,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            modifier = Modifier.fillMaxSize()
        )
    }
}
