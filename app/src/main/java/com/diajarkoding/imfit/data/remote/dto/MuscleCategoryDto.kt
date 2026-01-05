package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.MuscleCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MuscleCategoryDto(
    val id: Int,
    val name: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("icon_name")
    val iconName: String? = null,
    @SerialName("sort_order")
    val sortOrder: Int = 0
)

fun MuscleCategoryDto.toDomain(): MuscleCategory? {
    return try {
        MuscleCategory.valueOf(name)
    } catch (e: IllegalArgumentException) {
        null
    }
}
