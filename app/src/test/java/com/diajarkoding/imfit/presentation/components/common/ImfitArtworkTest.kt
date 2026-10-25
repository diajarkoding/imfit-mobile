package com.diajarkoding.imfit.presentation.components.common

import com.diajarkoding.imfit.domain.model.MuscleCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ImfitArtworkTest {
    @Test fun chest() = assertEquals("chest.svg", categoryArtAsset(MuscleCategory.CHEST))
    @Test fun back() = assertEquals("back.svg", categoryArtAsset(MuscleCategory.BACK))
    @Test fun shoulders() = assertEquals("shoulders.svg", categoryArtAsset(MuscleCategory.SHOULDERS))
    @Test fun biceps() = assertEquals("biceps.svg", categoryArtAsset(MuscleCategory.BICEPS))
    @Test fun triceps() = assertEquals("triceps.svg", categoryArtAsset(MuscleCategory.TRICEPS))
    @Test fun legs() = assertEquals("upper-legs.svg", categoryArtAsset(MuscleCategory.LEGS))
    @Test fun core() = assertEquals("core.svg", categoryArtAsset(MuscleCategory.CORE))
    @Test fun cardio() = assertEquals("cardio.svg", categoryArtAsset(MuscleCategory.CARDIO))
    @Test fun missingCategory() = assertEquals("all-muscles.svg", categoryArtAsset(null))
}
