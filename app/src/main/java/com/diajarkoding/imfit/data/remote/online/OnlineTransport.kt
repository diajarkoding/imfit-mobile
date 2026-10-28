package com.diajarkoding.imfit.data.remote.online

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class AuthIdentity(val ownerId: String, val epoch: Long)

/** No token-bearing data class/toString; never log the credential or SDK exceptions. */
class RequestCredential(val identity: AuthIdentity, internal val accessToken: String)

/** Identity comes from authenticated session, not profile fetch. Not installed in runtime DI in F2. */
class OnlineAuth {
    // Runtime bridges synchronise the SDK status before capture AND before accepting a response.
    // The bridge must use identity.value/signIn/refreshToken, never recurse through capture.
    internal var synchronizeSession: () -> Unit = {}
    private var nextEpoch = 0L
    private var credential: RequestCredential? = null
    private val mutableIdentity = MutableStateFlow<AuthIdentity?>(null)
    private val identityListeners = mutableListOf<() -> Unit>()
    val identity = mutableIdentity.asStateFlow()

    @Synchronized fun signIn(ownerId: String, accessToken: String): AuthIdentity {
        require(ownerId.isNotBlank() && accessToken.isNotBlank())
        val id = AuthIdentity(ownerId, ++nextEpoch)
        credential = RequestCredential(id, accessToken)
        mutableIdentity.value = id
        identityListeners.forEach { it() }
        return id
    }
    @Synchronized fun signOut() {
        ++nextEpoch
        credential = null
        mutableIdentity.value = null
        identityListeners.forEach { it() }
    }
    @Synchronized fun refreshToken(identity: AuthIdentity, accessToken: String) {
        require(accessToken.isNotBlank())
        if (credential?.identity != identity) throw StaleAuthException()
        credential = RequestCredential(identity, accessToken)
    }
    @Synchronized fun capture(): RequestCredential {
        synchronizeSession()
        return credential ?: throw OnlineFailure(RemoteError.Unauthenticated)
    }
    @Synchronized fun isCurrent(identity: AuthIdentity): Boolean {
        synchronizeSession()
        return credential?.identity == identity
    }
    @Synchronized internal fun <T> withCurrent(identity: AuthIdentity, action: () -> T): T {
        synchronizeSession()
        if (credential?.identity != identity) throw StaleAuthException()
        return action()
    }
    @Synchronized internal fun <T> synchronizedState(action: () -> T): T = action()
    @Synchronized internal fun onIdentityChanged(listener: () -> Unit): AutoCloseable {
        identityListeners += listener
        return AutoCloseable { synchronized(this) { identityListeners -= listener } }
    }
}

class StaleAuthException : CancellationException("Authentication epoch changed")

data class RpcResponse(val status: Int, val body: String)

fun interface OnlineTransport {
    suspend fun post(name: String, body: JsonObject, credential: RequestCredential): RpcResponse
}

/**
 * Dedicated client with no Auth/automatic retry/logging plugin should be supplied by future DI.
 * Bypasses SDK 3.0.3's lossy PostgrestImpl.parseErrorResponse (which discards SQLSTATE code).
 */
class KtorOnlineTransport(
    private val client: HttpClient,
    baseUrl: String,
    private val publishableKey: String,
) : OnlineTransport {
    private val endpoint = baseUrl.trimEnd('/') + "/rest/v1/rpc/"
    init {
        val uri = URI(baseUrl)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")))
    }
    override suspend fun post(name: String, body: JsonObject, credential: RequestCredential): RpcResponse {
        require(Regex("[a-z_]+").matches(name))
        val response = client.post(endpoint + name) {
            // Must preserve raw error body instead of throwing an exception containing headers.
            expectSuccess = false
            header("apikey", publishableKey)
            header(HttpHeaders.Authorization, "Bearer ${credential.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return RpcResponse(response.status.value, response.bodyAsText())
    }
}

@Serializable
data class PostgrestError(val code: String, val message: String, val details: String?, val hint: String?)

sealed interface RemoteError {
    data class Validation(val discriminator: String, val details: JsonObject?) : RemoteError
    data class Conflict(val discriminator: String, val details: JsonObject?) : RemoteError
    data object NotFound : RemoteError
    data object Unauthenticated : RemoteError
    data object Forbidden : RemoteError
    data object ContractUnavailable : RemoteError
    data class Engine(val code: String) : RemoteError
    data object Network : RemoteError
    data object Protocol : RemoteError
    data object UnknownOutcome : RemoteError
    data object UnresolvedMutation : RemoteError
    data object IdempotencyMismatch : RemoteError
}

class OnlineFailure(val reason: RemoteError) : Exception("Online request failed: ${reason.javaClass.simpleName}")

internal fun mapRemoteError(response: RpcResponse): RemoteError {
    val envelope = runCatching { onlineJson.decodeFromString<PostgrestError>(response.body) }.getOrNull()
    val details = envelope?.details?.let { runCatching { onlineJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    return when {
        response.status == 401 -> RemoteError.Unauthenticated
        envelope?.code == "42501" || response.status == 403 -> RemoteError.Forbidden
        envelope?.code in setOf("PGRST202", "PGRST204", "PGRST205", "42883") -> RemoteError.ContractUnavailable
        envelope?.code == "PT400" -> RemoteError.Validation(envelope.message, details)
        envelope?.code == "PT409" -> RemoteError.Conflict(envelope.message, details)
        envelope?.code == "PT404" -> RemoteError.NotFound
        envelope?.code in setOf("40001", "40P01") -> RemoteError.Engine(envelope!!.code)
        else -> RemoteError.Protocol // Not generic empty, NOT_FOUND, or version conflict.
    }
}
