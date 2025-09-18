package com.diajarkoding.imfit.domain.model

data class User(
    val fullname: String,
    val username: String,
    val email: String,
    val dateOfBirth: String?,
    val profilePictureUrl: String?
)