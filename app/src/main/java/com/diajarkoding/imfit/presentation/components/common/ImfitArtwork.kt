package com.diajarkoding.imfit.presentation.components.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.MuscleCategory

/**
 * Phase 1 — Assets Foundation (PRD v1.2.2 baseline).
 * Hero + category illustration resolvers backed by local WebP in drawable-nodpi.
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

/**
 * Typed adapter from the domain [MuscleCategory] enum to local artwork.
 * Null (unknown/unmapped category at the presentation boundary) resolves to the
 * neutral generic fallback so no card ever renders without an icon.
 *
 * Known limitation (Phase 1): unknown remote categories coerced to CHEST upstream
 * in the repositories render the Chest artwork, not generic. Full unknown-category
 * handling is deferred to the data-integration stage.
 */
@DrawableRes
fun categoryArtRes(category: MuscleCategory?): Int = when (category) {
    MuscleCategory.CHEST -> R.drawable.imfit_muscle_dada
    MuscleCategory.BACK -> R.drawable.imfit_muscle_punggung
    MuscleCategory.SHOULDERS -> R.drawable.imfit_muscle_bahu
    MuscleCategory.BICEPS -> R.drawable.imfit_muscle_biceps
    MuscleCategory.TRICEPS -> R.drawable.imfit_muscle_triceps
    MuscleCategory.LEGS -> R.drawable.imfit_muscle_kaki
    MuscleCategory.CORE -> R.drawable.imfit_muscle_inti
    MuscleCategory.CARDIO -> R.drawable.imfit_muscle_kardio
    null -> R.drawable.imfit_muscle_generic
}
