package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.MuscleCategory

/**
 * Phase 1 — Assets Foundation (PRD v1.2.2 baseline).
 * Hero illustrations use local WebP; muscle artwork uses local SVG assets.
 * Illustrations are purely decorative; readable content stays in Compose text.
 */
enum class ImfitHeroKind { HOME, PROGRESS }

@Composable
fun ImfitHeroIllustration(kind: ImfitHeroKind, modifier: Modifier = Modifier) {
    val res = when (kind) {
        ImfitHeroKind.HOME -> R.drawable.imfit_hero_home
        ImfitHeroKind.PROGRESS -> R.drawable.imfit_hero_progress
    }
    Image(
        painter = painterResource(res),
        contentDescription = null, // purely decorative
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

fun categoryArtAsset(category: MuscleCategory?): String = when (category) {
    MuscleCategory.CHEST -> "chest.svg"
    MuscleCategory.BACK -> "back.svg"
    MuscleCategory.SHOULDERS -> "shoulders.svg"
    MuscleCategory.BICEPS -> "biceps.svg"
    MuscleCategory.TRICEPS -> "triceps.svg"
    MuscleCategory.LEGS -> "upper-legs.svg"
    MuscleCategory.CORE -> "core.svg"
    MuscleCategory.CARDIO -> "cardio.svg"
    null -> "all-muscles.svg"
}

@Composable
fun MuscleCategoryArtwork(asset: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = "file:///android_asset/muscle-categories/$asset",
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}
