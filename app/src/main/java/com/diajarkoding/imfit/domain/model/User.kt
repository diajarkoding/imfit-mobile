package com.diajarkoding.imfit.domain.model

data class User(
    val id: String,
    val name: String,
    val email: String,
    val birthDate: String? = null,
    val profilePhotoUri: String? = null
)