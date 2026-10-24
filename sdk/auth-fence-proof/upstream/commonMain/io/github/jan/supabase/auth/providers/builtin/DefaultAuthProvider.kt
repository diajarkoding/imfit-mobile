package io.github.jan.supabase.auth.providers.builtin

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.AuthImpl
import io.github.jan.supabase.auth.AuthFlowPurpose
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.AuthProvider
import io.github.jan.supabase.auth.putCodeChallenge
import io.github.jan.supabase.auth.redirectTo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.putJsonObject
import io.github.jan.supabase.supabaseJson
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A default authentication provider
 * @see Email
 * @see Phone
 * @see IDToken
 */
sealed interface DefaultAuthProvider<C, R> : AuthProvider<C, R> {

    /**
     * The grant type of the provider.
     */
    val grantType: String

    /**
     * The default configuration for the provider.
     * @param captchaToken The captcha token when having captcha enabled
     * @param data Extra data for the user
     */
    @Serializable
    sealed class Config(
        @Serializable(with = CaptchaTokenSerializer::class)
        @SerialName("gotrue_meta_security")
        var captchaToken: String? = null,
        var data: JsonObject? = null,
    )

    override suspend fun login(
        supabaseClient: SupabaseClient,
        onSuccess: suspend (UserSession) -> Unit,
        redirectUrl: String?,
        config: (C.() -> Unit)?
    ) {
        val gotrue = supabaseClient.auth as AuthImpl
        gotrue.withAuthOperation { call ->
        require(config != null) { "Credentials are required" }
        val encodedCredentials = encodeCredentials(config)
        val url = "token?grant_type=$grantType"
        val response = gotrue.api.postJson(url, encodedCredentials) {
            redirectUrl?.let { redirectTo(it) }
        }
        response.body<UserSession>().also {
            gotrue.sessionAuthority.validate(call.operation)
            onSuccess(it)
        }
        }
    }

    @OptIn(SupabaseExperimental::class)
    override suspend fun signUp(
        supabaseClient: SupabaseClient,
        onSuccess: suspend (UserSession) -> Unit,
        redirectUrl: String?,
        config: (C.() -> Unit)?
    ): R? {
        val gotrue = supabaseClient.auth as AuthImpl
        return gotrue.withAuthOperation { call ->
        require(config != null) { "Credentials are required" }
        val body = encodeCredentials(config)
        val target = if (this == IDToken) redirectUrl else gotrue.externalRedirect(redirectUrl)
        val codeChallenge = if (this == IDToken) null else gotrue.prepareProviderCodeChallenge(AuthFlowPurpose.SIGNUP)
        val url = when (this) {
            Email -> "signup"
            Phone -> "signup"
            IDToken -> "token?grant_type=id_token"
        }
        val response = gotrue.api.postJson(url, buildJsonObject {
            putJsonObject(body)
            if (codeChallenge != null) {
                putCodeChallenge(codeChallenge)
            }
        }) {
            target?.let { redirectTo(it) }
        }
        val json = response.body<JsonObject>()
        gotrue.sessionAuthority.validate(call.operation)
        if(json.containsKey("access_token")) {
            val userSession = supabaseJson.decodeFromJsonElement<UserSession>(json)
            onSuccess(userSession)
            return@withAuthOperation null
        }
        decodeResult(json)
        }
    }

    @SupabaseInternal
    fun decodeResult(json: JsonObject): R

    @SupabaseInternal
    fun encodeCredentials(credentials: C.() -> Unit): JsonObject

}
