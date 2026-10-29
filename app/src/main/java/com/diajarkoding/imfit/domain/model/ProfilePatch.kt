package com.diajarkoding.imfit.domain.model

/** Omitted keeps the server value; Value(null) explicitly clears a nullable field. */
sealed interface ProfileField<out T> {
    data object Omitted : ProfileField<Nothing>
    data class Value<T>(val value: T) : ProfileField<T>
}

data class ProfilePatch(
    val name: ProfileField<String> = ProfileField.Omitted,
    val birthDate: ProfileField<String?> = ProfileField.Omitted,
    val avatarPath: ProfileField<String?> = ProfileField.Omitted,
)
