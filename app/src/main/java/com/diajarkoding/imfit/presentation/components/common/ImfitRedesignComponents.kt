package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.theme.*

@Composable
fun ImfitPageHeader(title: String, onProfile: () -> Unit, brand: Boolean = false, profilePhoto: String? = null) {
    val profileLabel = stringResource(R.string.redesign_profile)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (brand) {
            Box(Modifier.size(40.dp).clip(IMFITShapes.IconContainer).background(IMFITColors.gradientBrush), contentAlignment = Alignment.Center) {
                Icon(Symbols.Default.FitnessCenter, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(IMFITSpacing.md))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        FilledTonalIconButton(onClick = onProfile, modifier = Modifier.size(48.dp).semantics { contentDescription = profileLabel }) {
            if (profilePhoto.isNullOrBlank()) Icon(Symbols.Default.Person, null)
            else IMFITProfilePhoto(profilePhoto, size = 40.dp)
        }
    }
}

@Composable
fun ImfitSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ImfitHeroSummaryCard(kind: ImfitHeroKind, label: String, title: String, subtitle: String, footer: @Composable ColumnScope.() -> Unit = {}) {
    Card(shape = IMFITShapes.Card, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))) {
        Column(Modifier.fillMaxWidth().padding(IMFITSpacing.cardPaddingLarge)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ImfitHeroIllustration(kind, Modifier.widthIn(max = 132.dp).weight(0.7f).aspectRatio(640f / 425f))
            }
            footer()
        }
    }
}

@Composable
fun ImfitStatTile(value: String, label: String, modifier: Modifier = Modifier, icon: ImageVector = Symbols.Default.FitnessCenter, compact: Boolean = false) {
    Card(modifier, shape = IMFITShapes.CardSmall, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        if (compact) {
            Row(Modifier.fillMaxWidth().padding(IMFITSpacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(value, style = MaterialTheme.typography.titleMedium)
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
        Column(Modifier.fillMaxWidth().padding(IMFITSpacing.md), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
}

@Composable
fun ImfitWorkoutRow(title: String, subtitle: String, category: MuscleCategory?, subtitleMaxLines: Int = Int.MAX_VALUE, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.padding(IMFITSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            MuscleCategoryArtwork(categoryArtAsset(category), Modifier.size(56.dp).clip(IMFITShapes.IconContainer).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)))
            Spacer(Modifier.width(IMFITSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
            Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ImfitEmptyMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = IMFITSpacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ImfitStatGrid(tiles: List<Pair<String, String>>, icons: List<ImageVector>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp && LocalDensity.current.fontScale <= 1.15f) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            tiles.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                    row.forEachIndexed { columnIndex, tile ->
                        ImfitStatTile(tile.first, tile.second, Modifier.weight(1f).fillMaxHeight(), icons[rowIndex * columns + columnIndex])
                    }
                }
            }
        }
    }
}
