package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.remote.online.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.Delete
import com.diajarkoding.imfit.presentation.components.common.Symbols
import com.diajarkoding.imfit.theme.IMFITColors

@Composable
internal fun ActiveSetRow(
    set: WorkoutSet,
    exerciseName: String,
    weight: String,
    reps: String,
    mutationEnabled: Boolean,
    canDelete: Boolean,
    isPaused: Boolean,
    onUpdate: (String, String) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = mutationEnabled
) {
    val colors = IMFITColors.training
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp <= 340 || configuration.fontScale >= 1.25f
    val editable = inputEnabled && !set.isCompleted
    val weightLabel = stringResource(R.string.active_session_weight_input, exerciseName, set.setNumber)
    val repsLabel = stringResource(R.string.active_session_reps_input, exerciseName, set.setNumber)
    val completeLabel = stringResource(R.string.active_session_complete_set, exerciseName, set.setNumber)
    val deleteLabel = stringResource(R.string.active_session_delete_set, exerciseName, set.setNumber)

    val inputs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (compact) Text(stringResource(R.string.active_session_column_kg), color = colors.muted,
                    style = MaterialTheme.typography.labelSmall)
                SetNumberInput(weight, { raw -> onUpdate(raw, reps) }, weightLabel, editable, set.isCompleted, KeyboardType.Decimal)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (compact) Text(stringResource(R.string.active_session_column_reps), color = colors.muted,
                    style = MaterialTheme.typography.labelSmall)
                SetNumberInput(reps, { raw -> onUpdate(weight, raw) }, repsLabel, editable, set.isCompleted, KeyboardType.Number)
            }
        }
    }
    val actions: @Composable () -> Unit = {
        IconButton(onClick = onDelete, enabled = canDelete && mutationEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Symbols.Filled.Delete, deleteLabel, tint = if (canDelete && mutationEnabled) colors.error else colors.muted.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp))
        }
        Checkbox(
            checked = set.isCompleted,
            onCheckedChange = { onComplete() },
            enabled = mutationEnabled && editable && !isPaused && weight.replace(',', '.').toBigDecimalOrNull()?.let { it.signum() > 0 } == true &&
                reps.toIntOrNull()?.let { it > 0 } == true,
            modifier = Modifier.size(48.dp).semantics { contentDescription = completeLabel },
            colors = CheckboxDefaults.colors(
                checkedColor = colors.success,
                checkmarkColor = colors.surface,
                disabledCheckedColor = colors.success,
                disabledUncheckedColor = colors.muted.copy(alpha = 0.4f)
            )
        )
    }

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (set.isCompleted) colors.successContainer else Color.Transparent)
        .padding(vertical = 4.dp).testTag("active_set_${set.setNumber}")) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.active_workout_set_number, set.setNumber),
                    Modifier.weight(1f), color = if (set.isCompleted) colors.success else colors.muted,
                    style = MaterialTheme.typography.labelLarge)
                actions()
            }
            inputs()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(set.setNumber.toString(), Modifier.width(28.dp),
                    textAlign = TextAlign.Center, color = if (set.isCompleted) colors.success else colors.muted,
                    style = MaterialTheme.typography.labelSmall)
                Box(Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)) { inputs() }
                actions()
            }
        }
    }
}

@Composable
private fun SetNumberInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    completed: Boolean,
    keyboardType: KeyboardType
) {
    val colors = IMFITColors.training
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text,
            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
        decorationBox = { innerField ->
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(if (completed) colors.successContainer else colors.raised, RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, if (completed) colors.success.copy(alpha = 0.35f) else colors.border), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) Text("0", color = colors.muted, style = MaterialTheme.typography.bodyLarge)
                innerField()
            }
        }
    )
}
