package com.diajarkoding.imfit.data.auth

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoAuthDeepLinkHandler @Inject constructor() : AuthDeepLinkHandler {
    override fun handle(intent: Intent?, onAuthenticated: () -> Unit) = Unit
}
