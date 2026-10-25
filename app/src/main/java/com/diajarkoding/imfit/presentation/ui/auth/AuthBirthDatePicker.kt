package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuthBirthDatePicker(value: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val colors = IMFITColors.training
    val today = remember { LocalDate.now() }
    val initial = remember(value) { runCatching { LocalDate.parse(value) }.getOrDefault(today.minusYears(26)).coerceAtMost(today) }
    var selectedDate by rememberSaveable(value) { mutableStateOf(initial.toString()) }
    var displayedMonth by rememberSaveable(value) { mutableStateOf(YearMonth.from(initial).toString()) }
    var monthMenu by remember { mutableStateOf(false) }
    var yearMenu by remember { mutableStateOf(false) }
    val month = YearMonth.parse(displayedMonth)
    val selected = LocalDate.parse(selectedDate)
    val locale = Locale.getDefault()
    IMFITPlatformDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(12.dp).widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp), color = colors.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.label_date_of_birth), color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.auth_birth_date_description), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)),
                    color = colors.onPrimaryContainer, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(colors.primaryContainer, AuthControlShape).padding(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { displayedMonth = month.minusMonths(1).toString() }, enabled = month.year > today.year - 120 || month.monthValue > 1) {
                        Icon(Symbols.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.auth_previous_month), tint = colors.muted)
                    }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { monthMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(colors.raised, AuthControlShape)) {
                            Text(month.month.getDisplayName(TextStyle.FULL, locale), color = colors.text)
                        }
                        DropdownMenu(monthMenu, { monthMenu = false }) {
                            (1..12).forEach { number ->
                                val candidate = month.withMonth(number)
                                DropdownMenuItem(text = { Text(candidate.month.getDisplayName(TextStyle.FULL, locale)) },
                                    enabled = candidate <= YearMonth.from(today),
                                    onClick = { displayedMonth = candidate.toString(); monthMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        TextButton(onClick = { yearMenu = true }, modifier = Modifier.heightIn(min = 48.dp).background(colors.raised, AuthControlShape)) {
                            Text(month.year.toString(), color = colors.text)
                        }
                        DropdownMenu(yearMenu, { yearMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                            (today.year downTo today.year - 120).forEach { year ->
                                DropdownMenuItem(text = { Text(year.toString()) }, onClick = {
                                    displayedMonth = month.withYear(year).coerceAtMost(YearMonth.from(today)).toString()
                                    yearMenu = false
                                })
                            }
                        }
                    }
                    IconButton(onClick = { displayedMonth = month.plusMonths(1).toString() }, enabled = month < YearMonth.from(today)) {
                        Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.auth_next_month), tint = colors.muted)
                    }
                }
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(336.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            java.time.DayOfWeek.values().forEach { day ->
                                Text(day.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f), color = colors.muted,
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val offset = month.atDay(1).dayOfWeek.value - 1
                        val weeks = (offset + month.lengthOfMonth() + 6) / 7
                        repeat(weeks) { week ->
                            Row(Modifier.fillMaxWidth()) {
                                repeat(7) { weekday ->
                                    val day = week * 7 + weekday - offset + 1
                                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                                        if (day in 1..month.lengthOfMonth()) {
                                            val date = month.atDay(day)
                                            val isSelected = selected == date
                                            Box(Modifier.size(48.dp).clip(CircleShape)
                                                .semantics {
                                                    this.selected = isSelected
                                                    contentDescription = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))
                                                }
                                                .clickable(enabled = date <= today, role = Role.RadioButton) { selectedDate = date.toString() },
                                                contentAlignment = Alignment.Center) {
                                                Box(Modifier.size(36.dp).background(
                                                    if (isSelected) colors.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape),
                                                    contentAlignment = Alignment.Center) {
                                                    Text(day.toString(), color = if (isSelected) colors.onPrimary else colors.text,
                                                        fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_cancel), color = colors.primary)
                    }
                    Button(onClick = { onSelect(selectedDate) }, modifier = Modifier.heightIn(min = 48.dp),
                        shape = AuthControlShape,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                        Text(stringResource(R.string.auth_select_date))
                    }
                }
            }
        }
    }
}
