package io.github.jan.supabase.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.request.HttpRequestBuilder

/** Capture at initiation and retain only in the originating flow; never recover from stored credentials. */
suspend fun Auth.beginExternalFlow(): AuthExternalFlow = (this as AuthImpl).beginExternalFlow()

@SupabaseInternal
suspend fun Auth.parseFragmentAndImportSession(fragment: String, flow: AuthExternalFlow, onSessionSuccess: (UserSession) -> Unit = {}) {
    (this as AuthImpl).importExternalFragment(flow, fragment, onSessionSuccess)
}

@SupabaseInternal
fun HttpRequestBuilder.redirectTo(url: String) {
    this.url.parameters["redirect_to"] = url
}

internal fun invalidArg(message: String): Nothing = throw IllegalArgumentException(message)

internal expect suspend fun SupabaseClient.openExternalUrl(url: String)

internal expect suspend fun Auth.startExternalAuth(
    redirectUrl: String?,
    getUrl: suspend (redirectTo: String?) -> String,
    onSessionSuccess: suspend (UserSession) -> Unit
)
