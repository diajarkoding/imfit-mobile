package com.diajarkoding.imfit.data.auth

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import com.diajarkoding.imfit.data.sync.SyncLifecycleController
import com.diajarkoding.imfit.data.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseAuthDeepLinkHandler @Inject constructor(
    private val supabase: SupabaseClient,
    private val syncScheduler: SyncScheduler,
    private val syncLifecycleController: SyncLifecycleController,
) : AuthDeepLinkHandler {
    override fun handle(intent: Intent?, onAuthenticated: () -> Unit) {
        if (intent?.data?.scheme != "imfit" || intent.data?.host != "auth") return
        supabase.handleDeeplinks(intent) {
            supabase.auth.currentUserOrNull()?.id?.let { userId ->
                syncLifecycleController.unblock(userId)
                syncScheduler.enqueue(userId)
            }
            onAuthenticated()
        }
    }
}
