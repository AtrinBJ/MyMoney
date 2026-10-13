package com.mymoney.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One data series; [estimated] draws dashed (forecasts are never shown like recorded data, FBR-28). */
data class ChartSeries(val name: String, val values: List<Long>, val color: Color, val estimated: Boolean = false)

/**
 * Grouped bar chart drawn on Canvas. Time runs in reading direction (right-to-left in Persian).
 * Negative values are drawn below the zero line. [summary] is what TalkBack reads.
 */
@Composable
fun BarChart(
    labels: List<String>,
    series: List<ChartSeries>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val axis = MaterialTheme.colorScheme.outlineVariant
    val max = series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(0) ?: 0
    val min = series.flatMap { it.values }.minOrNull()?.coerceAtMost(0) ?: 0
    val span = (max - min).toFloat().takeIf { it > 0f } ?: 1f
    Column(modifier.semantics { contentDescription = summary }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = labels.size.coerceAtLeast(1)
            val groupW = size.width / n
            val barW = (groupW * 0.7f) / series.size.coerceAtLeast(1)
            val zeroY = size.height * (max / span)
            drawLine(axis, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
            for (i in 0 until n) {
                val slot = if (rtl) n - 1 - i else i
                val groupX = slot * groupW + groupW * 0.15f
                series.forEachIndexed { si, s ->
                    val v = s.values.getOrNull(i) ?: return@forEachIndexed
                    val h = kotlin.math.abs(v) / span * size.height
                    val top = if (v >= 0) zeroY - h else zeroY
                    drawRoundRect(
                        color = s.color.copy(alpha = if (s.estimated) 0.45f else 1f),
                        topLeft = Offset(groupX + si * barW, top),
                        size = Size(barW * 0.9f, h.coerceAtLeast(1f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l ->
                Text(l, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        Legend(series)
    }
}

/** Line chart for balances over time. */
@Composable
fun LineChart(
    labels: List<String>,
    series: List<ChartSeries>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val axis = MaterialTheme.colorScheme.outlineVariant
    val all = series.flatMap { it.values }
    val max = (all.maxOrNull() ?: 0).coerceAtLeast(0)
    val min = (all.minOrNull() ?: 0).coerceAtMost(0)
    val span = (max - min).toFloat().takeIf { it > 0f } ?: 1f
    Column(modifier.semantics { contentDescription = summary }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = labels.size
            if (n == 0) return@Canvas
            fun x(i: Int): Float {
                val step = if (n == 1) 0f else size.width / (n - 1)
                return if (rtl) size.width - i * step else i * step
            }
            fun y(v: Long): Float = size.height - (v - min) / span * size.height
            drawLine(axis, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
            series.forEach { s ->
                val path = Path()
                s.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                drawPath(
                    path, s.color,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = if (s.estimated) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
                    ),
                )
                s.values.forEachIndexed { i, v -> drawCircle(s.color, 3.dp.toPx(), Offset(x(i), y(v))) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            labels.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        Legend(series)
    }
}

@Composable
private fun Legend(series: List<ChartSeries>) {
    if (series.size < 2) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(s.name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
