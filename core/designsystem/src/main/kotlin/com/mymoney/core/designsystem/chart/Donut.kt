package com.mymoney.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class DonutSlice(val value: Long, val color: Color)

/**
 * Category share chart drawn on Canvas (no dependency; Vico has no pie charts).
 * [summary] is read by TalkBack instead of the drawing (docs/06 §6.8).
 */
@Composable
fun Donut(
    slices: List<DonutSlice>,
    summary: String,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    thickness: Dp = 22.dp,
    center: @Composable () -> Unit = {},
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0) }.toFloat()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size).semantics { contentDescription = summary }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (total <= 0f) return@Canvas
            val gap = if (slices.count { it.value > 0 } > 1) 2f else 0f
            var start = -90f
            slices.filter { it.value > 0 }.forEach { slice ->
                val sweep = slice.value / total * 360f
                drawArc(
                    color = slice.color,
                    startAngle = start + gap / 2,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        center()
    }
}
