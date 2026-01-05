package com.diajarkoding.imfit.domain.model

import androidx.annotation.StringRes
import com.diajarkoding.imfit.R

enum class MuscleCategory(@StringRes val stringResourceId: Int, val displayName: String) {
    CHEST(R.string.muscle_category_chest, "Chest"),
    BACK(R.string.muscle_category_back, "Back"),
    SHOULDERS(R.string.muscle_category_shoulders, "Shoulders"),
    BICEPS(R.string.muscle_category_biceps, "Biceps"),
    TRICEPS(R.string.muscle_category_triceps, "Triceps"),
    LEGS(R.string.muscle_category_legs, "Legs"),
    CORE(R.string.muscle_category_core, "Core"),
    CARDIO(R.string.muscle_category_cardio, "Cardio")
}
