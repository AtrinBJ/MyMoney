package com.mymoney.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.mymoney.core.designsystem.theme.glass
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.money.Digits
import kotlin.math.abs

/**
 * Standard content card. With glass on: a frosted translucent panel with a light edge and a soft, diffuse
 * shadow; tappable cards sink slightly under the finger (spring). A custom [containerColor] keeps its hue
 * but becomes translucent too. Glass off: the classic solid tonal card.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = MyMoneyTheme.glass
    val shape = MaterialTheme.shapes.large
    val fill = when {
        containerColor == Color.Unspecified -> glass.cardTint
        glass.enabled -> containerColor.copy(alpha = 0.72f)
        else -> containerColor
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    var m = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
    if (glass.enabled) m = m.shadow(18.dp, shape, ambientColor = glass.shadow, spotColor = glass.shadow)
    m = m.glass(glass, shape, tint = fill)
    if (onClick != null) m = m.clip(shape).clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
    Column(m.padding(MyMoneyTheme.spacing.l)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            Spacer(Modifier.size(MyMoneyTheme.spacing.s))
        }
        content()
    }
}

/** Percent change vs a previous period. Arrow + sign + color; "good" direction depends on the metric. */
@Composable
fun ChangeBadge(current: Long, previous: Long, higherIsGood: Boolean, modifier: Modifier = Modifier) {
    if (previous == 0L) return
    val formats = LocalFormats.current
    val pct = (current - previous).toDouble() * 100.0 / abs(previous.toDouble())
    val up = pct >= 0
    val good = up == higherIsGood
    val color = if (good) MyMoneyTheme.finance.income else MyMoneyTheme.finance.expense
    val text = Digits.localize(String.format(java.util.Locale.ROOT, "%s%.1f%%", if (up) "+" else "−", abs(pct)), formats.settings.digits)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (up) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(Digits.isolateLtr(text), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Label + amount column used inside KPI rows. */
@Composable
fun KpiValue(
    label: String,
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    color: AmountColor = AmountColor.NEUTRAL,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(minor, currency, style = MaterialTheme.typography.titleMedium, color = color)
        footer?.invoke()
    }
}

/** Progress toward a limit; turns to warning/expense color past thresholds. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, warnAt: Float = 0.8f) {
    val color = when {
        fraction >= 1f -> MyMoneyTheme.finance.expense
        fraction >= warnAt -> MyMoneyTheme.finance.warning
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

/** Informational banner for partial data, e.g. missing exchange rates (docs/05 §5.3). */
@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MyMoneyTheme.finance.warningContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(MyMoneyTheme.spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MyMoneyTheme.finance.warning)
            Spacer(Modifier.width(MyMoneyTheme.spacing.s))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Marks a forecast/projection so it is never confused with a recorded figure (docs/06 EstimateBadge, FBR-28).
 */
@Composable
fun EstimateNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Text("≈", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
