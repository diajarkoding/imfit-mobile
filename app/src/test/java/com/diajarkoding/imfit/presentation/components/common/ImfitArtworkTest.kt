package com.diajarkoding.imfit.presentation.components.common

import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.MuscleCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ImfitArtworkTest {
    @Test fun chest() = assertEquals(R.drawable.imfit_muscle_dada, categoryArtRes(MuscleCategory.CHEST))
    @Test fun back() = assertEquals(R.drawable.imfit_muscle_punggung, categoryArtRes(MuscleCategory.BACK))
    @Test fun shoulders() = assertEquals(R.drawable.imfit_muscle_bahu, categoryArtRes(MuscleCategory.SHOULDERS))
    @Test fun biceps() = assertEquals(R.drawable.imfit_muscle_biceps, categoryArtRes(MuscleCategory.BICEPS))
    @Test fun triceps() = assertEquals(R.drawable.imfit_muscle_triceps, categoryArtRes(MuscleCategory.TRICEPS))
    @Test fun legs() = assertEquals(R.drawable.imfit_muscle_kaki, categoryArtRes(MuscleCategory.LEGS))
    @Test fun core() = assertEquals(R.drawable.imfit_muscle_inti, categoryArtRes(MuscleCategory.CORE))
    @Test fun cardio() = assertEquals(R.drawable.imfit_muscle_kardio, categoryArtRes(MuscleCategory.CARDIO))
    @Test fun missingCategory() = assertEquals(R.drawable.imfit_muscle_generic, categoryArtRes(null))
}
