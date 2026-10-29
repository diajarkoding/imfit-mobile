package com.diajarkoding.imfit.data.auth

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseAuthDeepLinkHandler @Inject constructor(
    private val supabase: SupabaseClient,
) : AuthDeepLinkHandler {
    override fun handle(intent: Intent?, onAuthenticated: () -> Unit) {
        if (intent?.data?.scheme != "imfit" || intent.data?.host != "auth") return
        supabase.handleDeeplinks(intent) {
            onAuthenticated()
        }
    }
}
