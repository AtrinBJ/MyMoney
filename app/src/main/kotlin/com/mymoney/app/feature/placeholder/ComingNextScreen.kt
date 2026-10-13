package com.mymoney.app.feature.placeholder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mymoney.app.R
import com.mymoney.core.designsystem.component.EmptyState

/**
 * Honest placeholder for a tab that is not implemented yet. It never shows numbers.
 * Replaced by real screens in stages 3–4; must not exist in a release build.
 */
@Composable
fun ComingNextScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.Construction,
        title = stringResource(R.string.app_name),
        message = stringResource(R.string.state_coming_next),
        modifier = modifier,
    )
}
