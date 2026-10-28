package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.BuildConfig
import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.Exercise
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated read client: no SDK error parsing, automatic auth substitution, retry or logging. */
@Singleton
class ProductionReadGateway @Inject constructor(private val supabase: SupabaseClient) : RemoteReadGateway {
    override val auth = OnlineAuth()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observed: SessionStatus? = null
    private val client = HttpClient(Android) { install(HttpTimeout) { requestTimeoutMillis = 20_000 } }
    internal val repository by lazy {
        if (BuildConfig.SUPABASE_URL.isBlank()) throw OnlineFailure(RemoteError.ContractUnavailable)
        OnlineRepository(KtorOnlineTransport(client, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), auth)
    }
    private val tables by lazy { KtorReadTables(client, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, auth) }
    init {
        auth.synchronizeSession = ::synchronize
        synchronize()
        scope.launch { supabase.auth.sessionStatus.collect { synchronize() } }
    }
    private fun synchronize() = auth.synchronizedState {
        val status = supabase.auth.sessionStatus.value
        if (observed === status) return@synchronizedState
        observed = status
        when (status) {
            is SessionStatus.Authenticated -> {
                val owner = status.session.user?.id ?: run { auth.signOut(); return@synchronizedState }
                val current = auth.identity.value
                if (current == null || current.ownerId != owner || status.isNew) auth.signIn(owner, status.session.accessToken)
                else auth.refreshToken(current, status.session.accessToken)
            }
            is SessionStatus.NotAuthenticated -> if (auth.identity.value != null) auth.signOut()
            else -> Unit // Initialising/refresh failure is not authoritative absence or a new login.
        }
    }
    override suspend fun <T> read(call: RpcCall<T>): T {
        require(call.mutation == null) { "G3 gateway is read-only" }
        return repository.execute(call)
    }
    override suspend fun <T> readFor(identity: AuthIdentity, call: RpcCall<T>): T {
        require(call.mutation == null)
        return repository.execute(call, expectedIdentity = identity)
    }
    override suspend fun saveTemplate(request: com.diajarkoding.imfit.data.remote.TemplateWriteRequest) = repository.execute(OnlineRpc.saveTemplate(request))
    override suspend fun profile() = tables.profile()
    override suspend fun exercises(ids: List<String>) = tables.exercises(ids)
}
