package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Keep both decisions readable at compact widths and with enlarged text. */
@Composable
internal fun WorkoutDialogActions(
    secondary: @Composable () -> Unit,
    primary: @Composable () -> Unit
) {
    val fontScale = LocalConfiguration.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale >= 1.25f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                secondary()
                primary()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { secondary() }
                Box(Modifier.weight(1f)) { primary() }
            }
        }
    }
}
