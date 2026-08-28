package com.diajarkoding.imfit.domain.model

sealed interface RegisterResult {
    data class Authenticated(val user: User) : RegisterResult
    data object CheckEmail : RegisterResult
}
