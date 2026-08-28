package com.diajarkoding.imfit.data.auth

import android.content.Intent

interface AuthDeepLinkHandler {
    fun handle(intent: Intent?, onAuthenticated: () -> Unit)
}
