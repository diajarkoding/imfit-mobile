package com.diajarkoding.imfit.core.utils

import android.util.Patterns

object Validator {

    fun isNotEmpty(text: String): Boolean {
        return text.isNotBlank()
    }

    fun isUsernameValid(email: String): Boolean {
        return Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
    fun isEmailValid(email: String): Boolean {
        return Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    fun isPasswordValid(password: String): Boolean {
        // Minimal 8 karakter, 1 huruf besar, 1 huruf kecil, 1 angka, 1 karakter spesial
        val passwordPattern =
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#\$%^&*()_+]).{8,}\$".toRegex()
        return passwordPattern.matches(password)
    }
}