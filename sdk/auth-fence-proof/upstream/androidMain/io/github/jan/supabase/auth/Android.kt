package io.github.jan.supabase.auth

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.user.UserSession

internal fun openUrl(uri: Uri, action: ExternalAuthAction) {
    when(action) {
        ExternalAuthAction.ExternalBrowser -> {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
            browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            applicationContext().startActivity(browserIntent)
        }
        is ExternalAuthAction.CustomTabs -> {
            val intent = CustomTabsIntent.Builder().apply(action.intentBuilder).build()
            intent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.launchUrl(applicationContext(), uri)
        }
    }
}

//TODO: Add context receiver 'Activity'
/**
 * Handle deeplinks for authentication.
 * This handles the deeplinks for implicit and PKCE flow.
 * @param intent The intent from the activity
 * @param onSessionSuccess The callback when the session was successfully imported
 */
@OptIn(SupabaseInternal::class)
suspend fun SupabaseClient.handleDeeplinks(intent: Intent, flow: AuthExternalFlow, onSessionSuccess: (UserSession) -> Unit = {}) {
    val data = intent.data ?: return
    val scheme = data.scheme ?: return
    val host = data.host ?: return
    if(scheme != auth.config.scheme || host != auth.config.host) return
    when(this.auth.config.flowType) {
        FlowType.IMPLICIT -> {
            val fragment = data.fragment ?: return
            auth.parseFragmentAndImportSession(fragment, flow, onSessionSuccess)
        }
        FlowType.PKCE -> {
            val code = data.getQueryParameter("code") ?: return
            check(data.getQueryParameters("state").size == 1) { "Unverified external flow state" }
            (auth as AuthImpl).importExternalCode(flow, data.getQueryParameter("state"), code, onSessionSuccess)
        }
    }
}
