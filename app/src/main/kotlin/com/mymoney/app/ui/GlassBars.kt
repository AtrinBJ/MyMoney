package com.mymoney.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoney.app.navigation.TopLevelDestination
import com.mymoney.core.designsystem.theme.Backdrop
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.designsystem.theme.glass

/**
 * Floating glass tab bar (docs/06 §6.9). The selection highlight slides between tabs with a "liquid" stretch:
 * its leading edge moves faster than its trailing edge, then both settle with a soft spring.
 */
@Composable
fun GlassNavBar(selected: TopLevelDestination?, onSelect: (TopLevelDestination) -> Unit, backdrop: Backdrop) {
    val glass = MyMoneyTheme.glass
    val shape = RoundedCornerShape(32.dp)
    val tabs = TopLevelDestination.entries
    val index = selected?.let { tabs.indexOf(it) } ?: -1
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(66.dp)
                .shadow(20.dp, shape, ambientColor = glass.shadow, spotColor = glass.shadow)
                .glass(glass, shape, backdrop = backdrop),
        ) {
            val itemWidth = maxWidth / tabs.size
            if (index >= 0) {
                val previous = remember { mutableIntStateOf(index) }
                val forward = index >= previous.intValue
                previous.intValue = index
                val fast = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
                val slow = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)
                val start by animateDpAsState(itemWidth * index, if (forward) slow else fast, label = "start")
                val end by animateDpAsState(itemWidth * (index + 1), if (forward) fast else slow, label = "end")
                Box(
                    Modifier.offset(x = start).width(end - start).fillMaxHeight().padding(6.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (glass.dark) 0.24f else 0.14f)),
                )
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, tab ->
                    val isSelected = i == index
                    val tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                            .selectable(selected = isSelected, role = Role.Tab, indication = ripple(), interactionSource = null) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(if (isSelected) tab.selectedIcon else tab.unselectedIcon, contentDescription = null, tint = tint)
                        Text(
                            stringResource(tab.label), style = MaterialTheme.typography.labelSmall, color = tint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Frosted strip behind the top app bar; content scrolling underneath is blurred. */
@Composable
fun GlassTopBar(backdrop: Backdrop, content: @Composable () -> Unit) {
    val glass = MyMoneyTheme.glass
    Box(Modifier.fillMaxWidth().glass(glass, RectangleShape, backdrop = backdrop)) { content() }
}
