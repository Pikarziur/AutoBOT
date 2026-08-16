package com.autobot.app.ui.tasks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch

/**
 * 触摸标记叠加层
 *
 * 在预览 SurfaceView 上方叠加显示最近触摸点位置
 * 标记会按虚拟显示器坐标等比缩放到 View 坐标，再绘制圆环动画
 *
 * 注意：markers 中的 (x,y) 是虚拟显示器坐标，
 * 这里在 DrawScope 中根据 bufferWidth/bufferHeight 做等比缩放映射到 View 坐标
 */
@Composable
fun TouchPreviewOverlay(
    markers: List<MonitorViewModel.TouchMarker>,
    bufferWidth: Int,
    bufferHeight: Int,
    modifier: Modifier = Modifier
) {
    // 标记动画进度（每个 marker 进入时从 0 -> 1 衰减）
    val animatable = remember { Animatable(1f) }

    LaunchedEffect(markers) {
        if (markers.isNotEmpty()) {
            animatable.snapTo(0f)
            animatable.animateTo(1f, animationSpec = tween(durationMillis = 600))
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (bufferWidth <= 0 || bufferHeight <= 0) return@Canvas

        // 等比缩放：取较小比例保证标记位置与预览画面一致
        val scale = minOf(
            size.width / bufferWidth,
            size.height / bufferHeight
        )
        // 居中偏移（与全屏坐标映射保持一致）
        val offsetX = (size.width - bufferWidth * scale) / 2f
        val offsetY = (size.height - bufferHeight * scale) / 2f

        val progress = animatable.value
        val baseRadius = 24f * (1f - progress * 0.6f)
        val ringRadius = baseRadius + 18f * progress
        val alpha = (1f - progress).coerceIn(0f, 1f)

        markers.forEach { marker ->
            val vx = marker.x * scale + offsetX
            val vy = marker.y * scale + offsetY

            // 外圈圆环（动画扩散）
            drawCircle(
                color = Color(0xFFFF3B30).copy(alpha = alpha),
                radius = ringRadius,
                center = androidx.compose.ui.geometry.Offset(vx, vy),
                style = Stroke(width = 3f)
            )
            // 内圈实心点
            drawCircle(
                color = Color(0xFFFF3B30).copy(alpha = alpha),
                radius = 6f,
                center = androidx.compose.ui.geometry.Offset(vx, vy)
            )
        }
    }
}
