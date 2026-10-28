package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.*

/** Only the two explicitly granted direct read surfaces. No generic business-table read escape. */
class KtorReadTables(private val client: HttpClient, private val base: String, private val key: String, private val auth: OnlineAuth) {
    suspend fun profile(): RemoteProfile {
        val owner = auth.capture().identity.ownerId
        val rows = get<List<RemoteProfile>>("profiles", mapOf("select" to "id,name,email,birth_date,avatar_url", "id" to "eq.$owner", "limit" to "1"))
        if (rows.isEmpty()) throw OnlineFailure(RemoteError.NotFound)
        return rows.singleOrNull()?.takeIf { it.id == owner } ?: throw OnlineFailure(RemoteError.Protocol)
    }
    suspend fun exercises(ids: List<String>): List<com.diajarkoding.imfit.domain.model.Exercise> {
        require(ids.size <= 100)
        if (ids.isEmpty()) return emptyList()
        require(ids.all { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char in "_-" } })
        val rows = get<List<ExerciseDto>>("exercises", mapOf("select" to "*", "id" to "in.(${ids.distinct().joinToString(",")})", "is_active" to "eq.true", "limit" to "100"))
        if (rows.size > ids.distinct().size || rows.map { it.id }.distinct().size != rows.size ||
            rows.any { it.id !in ids || !it.isActive || it.muscleCategoryId !in 1..8 }) throw OnlineFailure(RemoteError.Protocol)
        return rows.map { it.toDomain() }
    }
    private suspend inline fun <reified T> get(table: String, query: Map<String, String>): T {
        if (base.isBlank()) throw OnlineFailure(RemoteError.ContractUnavailable)
        // Reuse the RPC transport's HTTPS/loopback URL validation; no request is made here.
        KtorOnlineTransport(client, base, key)
        val credential = auth.capture()
        val response = try {
            client.get(base.trimEnd('/') + "/rest/v1/" + table) {
                expectSuccess = false
                header("apikey", key)
                header(HttpHeaders.Authorization, "Bearer ${credential.accessToken}")
                query.forEach { (name, value) -> parameter(name, value) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (!auth.isCurrent(credential.identity)) throw StaleAuthException()
            throw OnlineFailure(RemoteError.Network)
        }
        val body = response.bodyAsText()
        currentCoroutineContext().ensureActive()
        if (!auth.isCurrent(credential.identity)) throw StaleAuthException()
        if (response.status.value !in 200..299) throw OnlineFailure(mapRemoteError(RpcResponse(response.status.value, body)))
        return try { onlineJson.decodeFromString<T>(body) }
        catch (_: kotlinx.serialization.SerializationException) { throw OnlineFailure(RemoteError.Protocol) }
    }
}
