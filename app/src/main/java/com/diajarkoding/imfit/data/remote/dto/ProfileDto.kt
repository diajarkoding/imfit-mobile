package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileDto(
    val id: String,
    val name: String,
    val email: String,
    @SerialName("birth_date")
    val birthDate: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

fun ProfileDto.toDomain(): User {
    return User(
        id = id,
        name = name,
        email = email,
        birthDate = birthDate,
        profilePhotoUri = avatarUrl
    )
}

fun User.toDto(): ProfileDto {
    return ProfileDto(
        id = id,
        name = name,
        email = email,
        birthDate = birthDate,
        avatarUrl = profilePhotoUri
    )
}
