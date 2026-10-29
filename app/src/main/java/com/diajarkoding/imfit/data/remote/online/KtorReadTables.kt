package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.ProfileField
import com.diajarkoding.imfit.domain.model.ProfilePatch
import com.diajarkoding.imfit.domain.repository.isCanonicalAvatarPath
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.*

/** Only the two explicitly granted direct read surfaces. No generic business-table read escape. */
class KtorReadTables(private val client: HttpClient, private val base: String, private val key: String, private val auth: OnlineAuth) {
    suspend fun profile(): RemoteProfile {
        val credential = auth.capture()
        return profileRow(get("profiles", profileQuery(credential.identity.ownerId), credential), credential.identity)
    }

    suspend fun updateProfile(patch: ProfilePatch): RemoteProfile {
        val credential = auth.capture()
        val body = buildJsonObject {
            when (val field = patch.name) {
                ProfileField.Omitted -> Unit
                is ProfileField.Value -> put("name", field.value)
            }
            when (val field = patch.birthDate) {
                ProfileField.Omitted -> Unit
                is ProfileField.Value -> put("birth_date", field.value?.let(::JsonPrimitive) ?: JsonNull)
            }
            when (val field = patch.avatarPath) {
                ProfileField.Omitted -> Unit
                is ProfileField.Value -> {
                    require(field.value == null || isCanonicalAvatarPath(field.value, credential.identity.ownerId))
                    put("avatar_url", field.value?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        if (body.isEmpty()) return profileRow(get("profiles", profileQuery(credential.identity.ownerId), credential), credential.identity)
        val rows = request<List<RemoteProfile>>("profiles", profileQuery(credential.identity.ownerId), credential, HttpMethod.Patch, body)
        return profileRow(rows, credential.identity)
    }

    private fun profileQuery(owner: String) = mapOf(
        "select" to "id,name,email,birth_date,avatar_url", "id" to "eq.$owner")

    private fun profileRow(rows: List<RemoteProfile>, identity: AuthIdentity): RemoteProfile {
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        if (rows.isEmpty()) throw OnlineFailure(RemoteError.NotFound)
        val row = rows.singleOrNull() ?: throw OnlineFailure(RemoteError.Protocol)
        if (row.id != identity.ownerId || row.name.isBlank() || row.email.isBlank() ||
            (row.birthDate != null && runCatching { java.time.LocalDate.parse(row.birthDate) }.isFailure)) {
            throw OnlineFailure(RemoteError.Protocol)
        }
        return row
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
    private suspend inline fun <reified T> get(
        table: String, query: Map<String, String>, credential: RequestCredential = auth.capture(),
    ): T = request(table, query, credential, HttpMethod.Get, null)

    private suspend inline fun <reified T> request(
        table: String, query: Map<String, String>, credential: RequestCredential,
        method: HttpMethod, body: JsonObject?,
    ): T {
        if (base.isBlank()) throw OnlineFailure(RemoteError.ContractUnavailable)
        KtorOnlineTransport(client, base, key)
        if (!auth.isCurrent(credential.identity)) throw StaleAuthException()
        val response = try {
            client.request(base.trimEnd('/') + "/rest/v1/" + table) {
                this.method = method
                expectSuccess = false
                header("apikey", key)
                header(HttpHeaders.Authorization, "Bearer ${credential.accessToken}")
                query.forEach { (name, value) -> parameter(name, value) }
                if (body != null) {
                    header("Prefer", "return=representation")
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
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
