package io.github.jan.supabase.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.admin.AdminApi
import io.github.jan.supabase.auth.admin.AdminApiImpl
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthSessionMissingException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.mfa.MfaApi
import io.github.jan.supabase.auth.mfa.MfaApiImpl
import io.github.jan.supabase.auth.providers.AuthProvider
import io.github.jan.supabase.auth.providers.ExternalAuthConfigDefaults
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.providers.builtin.SSO
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.user.UserUpdateBuilder
import io.github.jan.supabase.bodyOrNull
import io.github.jan.supabase.exceptions.BadRequestRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.exceptions.UnknownRestException
import io.github.jan.supabase.logging.d
import io.github.jan.supabase.logging.e
import io.github.jan.supabase.logging.i
import io.github.jan.supabase.putJsonObject
import io.github.jan.supabase.safeBody
import io.github.jan.supabase.supabaseJson
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.utils.io.errors.IOException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

private const val SESSION_REFRESH_THRESHOLD = 0.8
@Suppress("MagicNumber") // see #631
private val SIGNOUT_IGNORE_CODES = listOf(401, 403, 404)

@PublishedApi
internal class AuthImpl(
    override val supabaseClient: SupabaseClient,
    override val config: AuthConfig
) : Auth {

    override val sessionAuthority = AtomicSessionAuthority()

    private val _sessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
    override val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()
    internal val authScope = CoroutineScope(config.coroutineDispatcher)
    override val sessionManager = config.sessionManager ?: createDefaultSessionManager()
    override val codeVerifierCache = config.codeVerifierCache ?: createDefaultCodeVerifierCache()

    @OptIn(SupabaseInternal::class)
    internal val api = supabaseClient.authenticatedSupabaseApi(this)
    override val admin: AdminApi = AdminApiImpl(this)
    override val mfa: MfaApi = MfaApiImpl(this)
    var sessionJob: Job? = null
        private set
    private var externalFlow: AuthExternalFlow? = null
    private var lifecyclePaused = false // same authority as session/job/flow publication
    private var refreshContext: AuthCallContext? = null
    internal var platformLifecycleJob: Job? by atomic(null)
    override val isAutoRefreshRunning: Boolean
        get() = sessionJob?.isActive == true

    override val serializer = config.serializer ?: supabaseClient.defaultSerializer

    override val apiVersion: Int
        get() = Auth.API_VERSION

    override val pluginKey: String
        get() = Auth.key

    internal suspend fun <T> withAuthOperation(block: suspend (AuthCallContext) -> T): T {
        val inherited = currentCoroutineContext()[AuthCallContext]
        if (inherited?.auth === this) {
            sessionAuthority.validate(inherited.operation)
            return block(inherited)
        }
        val (operation, session) = sessionAuthority.capture { currentSessionOrNull() }
        val call = AuthCallContext(this, operation, session)
        return withContext(call) { block(call) }
    }

    init {
        if(supabaseClient.accessToken != null) error("The Auth plugin is not available when using a custom access token provider. Please uninstall the Auth plugin.")
    }

    override fun init() {
        setupPlatform()
        authScope.launch {
            val startup = sessionAuthority.bootstrapOperation
            try {
                if (config.autoLoadFromStorage && loadFromStorageChecked(config.alwaysAutoRefresh, startup)) return@launch
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                // Do not leave awaitInitialization suspended or log credential-bearing failures.
                Auth.logger.e { "Session recovery unavailable; stored bytes are retained" }
            }
            try {
                sessionAuthority.mutate(startup, null, true, ::invalidateFailedSession) {
                    _sessionStatus.value = SessionStatus.NotAuthenticated(false)
                }
            } catch (_: StaleAuthOperationException) { /* Login, shutdown or a failed publication already superseded startup. */ }
        }
    }

    override suspend fun <C, R, Provider : AuthProvider<C, R>> signInWith(
        provider: Provider,
        redirectUrl: String?,
        config: (C.() -> Unit)?
    ) = withAuthOperation { call ->
        provider.login(supabaseClient, {
            importSessionChecked(it, this.config.alwaysAutoRefresh, SessionSource.SignIn(provider), call.operation)
        }, redirectUrl, config)
    }

    override suspend fun signInAnonymously(data: JsonObject?, captchaToken: String?) = withAuthOperation { call ->
        val response = api.postJson("signup", buildJsonObject {
            data?.let { put("data", it) }
            captchaToken?.let(::putCaptchaToken)
        })
        val session = response.safeBody<UserSession>()
        importSessionChecked(session, config.alwaysAutoRefresh, SessionSource.AnonymousSignIn, call.operation)
        Unit
    }

    override suspend fun <C, R, Provider : AuthProvider<C, R>> signUpWith(
        provider: Provider,
        redirectUrl: String?,
        config: (C.() -> Unit)?
    ): R? = withAuthOperation { call ->
        var published = false
        val result = provider.signUp(supabaseClient, {
            importSessionChecked(it, this.config.alwaysAutoRefresh, SessionSource.SignUp(provider), call.operation)
            published = true
        }, redirectUrl, config)
        if (!published) sessionAuthority.validate(call.operation)
        result
    }

    override suspend fun linkIdentity(
        provider: OAuthProvider,
        redirectUrl: String?,
        config: ExternalAuthConfigDefaults.() -> Unit
    ): String? = withAuthOperation { call ->
        val flow = requireExternalFlow()
        if (call.session?.user?.id == null) throw UnverifiedAuthFlowException(AuthFlowRejection.OWNER_MISMATCH)
        val automaticallyOpen = ExternalAuthConfigDefaults().apply(config).automaticallyOpenUrl
        val fetchUrl: suspend (String?) -> String = { redirectTo: String? ->
            val url = getOAuthUrl(provider, redirectTo, "user/identities/authorize", additionalConfig = config)
            val response = api.rawRequest(url) {
                method = HttpMethod.Get
            }
            response.request.url.toString()
        }
        if(!automaticallyOpen) {
            val result = fetchUrl(redirectUrl)
            sessionAuthority.local(call.operation) { ensureCurrentFlow(flow) }
            return@withAuthOperation result
        }
        startExternalAuth(
            redirectUrl = redirectUrl,
            getUrl = {
                fetchUrl(it)
            },
            onSessionSuccess = {
                importSession(it, source = SessionSource.UserIdentitiesChanged(it))
            }
        )
        sessionAuthority.validate(call.operation)
        null
    }

    override suspend fun unlinkIdentity(identityId: String, updateLocalUser: Boolean) = withAuthOperation { call ->
        api.delete("user/identities/$identityId")
        if (updateLocalUser) {
            val session = call.session ?: return@withAuthOperation
            val newUser = session.user?.copy(identities = session.user.identities?.filter { it.identityId != identityId })
            val newSession = session.copy(user = newUser)
            publishUpdatedUser(call, newSession, SessionSource.UserIdentitiesChanged(session))
        } else sessionAuthority.validate(call.operation)
    }

    override suspend fun retrieveSSOUrl(
        redirectUrl: String?,
        config: SSO.Config.() -> Unit
    ): SSO.Result = withAuthOperation { call ->
        val flow = requireExternalFlow()
        val target = externalRedirect(redirectUrl)
        val createdConfig = SSO.Config().apply(config)

        require((createdConfig.domain != null && createdConfig.domain!!.isNotBlank()) || (createdConfig.providerId != null && createdConfig.providerId!!.isNotBlank())) {
            "Either domain or providerId must be set"
        }

        require(createdConfig.domain == null || createdConfig.providerId == null) {
            "Either domain or providerId must be set, not both"
        }

        val codeChallenge: String? = preparePKCEIfEnabled(flow, AuthFlowPurpose.SSO)
        val result = api.postJson("sso", buildJsonObject {
            put("redirect_to", target)
            createdConfig.captchaToken?.let(::putCaptchaToken)
            codeChallenge?.let(::putCodeChallenge)
            createdConfig.domain?.let {
                put("domain", it)
            }
            createdConfig.providerId?.let {
                put("provider_id", it)
            }
        }).body<SSO.Result>()
        sessionAuthority.local(call.operation) { ensureCurrentFlow(flow) }
        result
    }

    override suspend fun updateUser(
        updateCurrentUser: Boolean,
        redirectUrl: String?,
        config: UserUpdateBuilder.() -> Unit
    ): UserInfo = withAuthOperation { call ->
        val updateBuilder = UserUpdateBuilder(serializer = serializer).apply(config)
        val emailFlow = if (updateBuilder.email != null) requireExternalFlow() else null
        if (emailFlow != null && call.session?.user?.id == null) throw UnverifiedAuthFlowException(AuthFlowRejection.OWNER_MISMATCH)
        val target = if (emailFlow != null) externalRedirect(redirectUrl) else redirectUrl
        val codeChallenge = emailFlow?.let { preparePKCEIfEnabled(it, AuthFlowPurpose.EMAIL_UPDATE) }
        val body = buildJsonObject {
            putJsonObject(supabaseJson.encodeToJsonElement(updateBuilder).jsonObject)
            codeChallenge?.let(::putCodeChallenge)
        }.toString()
        val response = api.putJson("user", body) {
            target?.let { url.parameters.append("redirect_to", it) }
        }
        val userInfo = response.safeBody<UserInfo>()
        if (updateCurrentUser && call.session != null) {
            val newSession = call.session.copy(user = userInfo)
            publishUpdatedUser(call, newSession, SessionSource.UserChanged(newSession))
        } else sessionAuthority.validate(call.operation)
        userInfo
    }

    private suspend fun publishUpdatedUser(call: AuthCallContext, session: UserSession, source: SessionSource) {
        val previousOwner = call.session?.user?.id
        check(previousOwner == null || previousOwner == session.user?.id) { "User response changed owner" }
        val refresh = isAutoRefreshRunning
        val flow = currentCoroutineContext()[AuthExternalContext]?.flow?.takeIf { it.purpose == AuthFlowPurpose.EMAIL_UPDATE }
        sessionAuthority.mutate(call.operation, session.user?.id, previousOwner != session.user?.id, ::invalidateFailedSession,
            guard = { flow == null || (flowIsCurrent(flow) && flow.call.operation === call.operation) },
            onCommitted = {
                // Transfer only this operation's exact, same-owner publication receipt, never capture B.
                if (flow != null) {
                    flow.call = AuthCallContext(this, it, session)
                    flow.completedOperation = it
                }
                if (refresh) scheduleRefresh(session, it)
            }) {
            if (config.autoSaveToStorage) sessionManager.saveSession(session)
            sessionJob?.cancel()
            sessionJob = null
            externalFlow = flow
            _sessionStatus.value = SessionStatus.Authenticated(session, source)
        }
    }

    private suspend fun resend(type: String, body: JsonObjectBuilder.() -> Unit) = withAuthOperation { call ->
        api.postJson("resend", buildJsonObject {
            put("type", type)
            putJsonObject(buildJsonObject(body))
        })
        sessionAuthority.validate(call.operation)
    }

    override suspend fun resendEmail(type: OtpType.Email, email: String, captchaToken: String?) =
        resend(type.type) {
            put("email", email)
            captchaToken?.let(::putCaptchaToken)
        }

    override suspend fun resendPhone(
        type: OtpType.Phone,
        phone: String,
        captchaToken: String?
    ) = resend(type.type) {
        put("phone", phone)
        captchaToken?.let(::putCaptchaToken)
    }

    override suspend fun resetPasswordForEmail(
        email: String,
        redirectUrl: String?,
        captchaToken: String?
    ) = withAuthOperation { call ->
        val flow = requireExternalFlow()
        val target = externalRedirect(redirectUrl)
        require(email.isNotBlank()) {
            "Email must not be blank"
        }
        val codeChallenge = preparePKCEIfEnabled(flow, AuthFlowPurpose.RECOVERY)
        val body = buildJsonObject {
            put("email", email)
            captchaToken?.let(::putCaptchaToken)
            codeChallenge?.let(::putCodeChallenge)
        }.toString()
        api.postJson("recover", body) {
            url.parameters.append("redirect_to", target)
        }
        sessionAuthority.local(call.operation) { ensureCurrentFlow(flow) }
    }

    override suspend fun reauthenticate() = withAuthOperation { call ->
        api.get("reauthenticate")
        sessionAuthority.validate(call.operation)
    }

    override suspend fun signOut(scope: SignOutScope) = withAuthOperation { call ->
        if (call.session != null) {
            try {
                api.post("logout") {
                    parameter("scope", scope.name.lowercase())
                }
            } catch(e: RestException) {
                if(e.statusCode in SIGNOUT_IGNORE_CODES) {
                    Auth.logger.d { "Received error code ${e.statusCode} while signing out user. This can happen if the user doesn't exist anymore or the JWT is invalid/expired. Proceeding to clean up local data..." }
                } else throw e
            }
            Auth.logger.d { "Logged out session in Supabase" }
        } else {
            Auth.logger.i { "Skipping session logout as there is no session available. Proceeding to clean up local data..." }
        }
        if (scope != SignOutScope.OTHERS && !call.identityClearedByThisOperation) {
            clearSessionChecked(call.operation)
        } else if (!call.identityClearedByThisOperation) sessionAuthority.validate(call.operation)
        Auth.logger.d { "Successfully logged out" }
    }

    private suspend fun verify(
        type: String,
        token: String?,
        captchaToken: String?,
        additionalData: JsonObjectBuilder.() -> Unit
    ) = withAuthOperation { call ->
        val body = buildJsonObject {
            put("type", type)
            token?.let { put("token", it) }
            captchaToken?.let(::putCaptchaToken)
            additionalData()
        }
        val response = api.postJson("verify", body)
        val session = response.body<UserSession>()
        importSessionChecked(session, config.alwaysAutoRefresh, SessionSource.SignIn(OTP), call.operation)
        Unit
    }

    override suspend fun verifyEmailOtp(
        type: OtpType.Email,
        email: String,
        token: String,
        captchaToken: String?
    ) = verify(type.type, token, captchaToken) {
        put("email", email)
    }

    override suspend fun verifyEmailOtp(
        type: OtpType.Email,
        tokenHash: String,
        captchaToken: String?
    ) = verify(type.type, null, captchaToken) {
        put("token_hash", tokenHash)
    }

    override suspend fun verifyPhoneOtp(
        type: OtpType.Phone,
        phone: String,
        token: String,
        captchaToken: String?
    ) = verify(type.type, token, captchaToken) {
        put("phone", phone)
    }

    override suspend fun retrieveUser(jwt: String): UserInfo = withAuthOperation { call ->
        val response = api.get("user") {
            headers["Authorization"] = "Bearer $jwt"
        }
        val body = response.bodyAsText()
        val user = supabaseJson.decodeFromString<UserInfo>(body)
        sessionAuthority.validate(call.operation)
        user
    }

    override suspend fun retrieveUserForCurrentSession(updateSession: Boolean): UserInfo = withAuthOperation { call ->
        val original = call.session ?: error("No session found")
        val user = retrieveUser(original.accessToken)
        if (updateSession) {
            publishUpdatedUser(call, original.copy(user = user), SessionSource.UserChanged(original))
        } else sessionAuthority.validate(call.operation)
        user
    }

    override suspend fun importAuthToken(
        accessToken: String,
        refreshToken: String,
        retrieveUser: Boolean,
        autoRefresh: Boolean,
    ) = withAuthOperation { call ->
        // Keep the original operation across lookup AND publication. The upstream default helper
        // catches stale/cancelled lookup and then recaptures a successor in importSession().
        val user = if (retrieveUser) {
            try { retrieveUser(accessToken) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                sessionAuthority.validate(call.operation)
                Auth.logger.e { "Token user lookup unavailable; no credential details logged" }
                null
            }
        } else null
        importSessionChecked(UserSession(accessToken, refreshToken, "", "", 0L, "", user),
            autoRefresh, SessionSource.Unknown, call.operation)
        Unit
    }

    /** Provider-side verifier writes use the same authority even for direct provider invocation.
     * This does NOT certify a raw callback as having opaque external-flow provenance.
     */
    internal suspend fun prepareProviderCodeChallenge(purpose: AuthFlowPurpose): String? =
        preparePKCEIfEnabled(requireExternalFlow(), purpose)

    override suspend fun exchangeCodeForSession(code: String, saveSession: Boolean, flow: AuthExternalFlow?, state: String?): UserSession {
        val active = flow ?: throw UnverifiedAuthFlowException(AuthFlowRejection.MISSING_PROVENANCE)
        return exchangeExternalCode(active, state, code, saveSession).session
    }

    override suspend fun refreshSession(refreshToken: String): UserSession = withAuthOperation { call ->
        Auth.logger.d {
            "Refreshing session"
        }
        val body = buildJsonObject {
            put("refresh_token", refreshToken)
        }
        val response = api.postJson("token?grant_type=refresh_token", body) {
            headers.remove("Authorization")
        }
        val session = response.safeBody<UserSession>("Auth#refreshSession")
        sessionAuthority.validate(call.operation)
        session
    }

    override suspend fun refreshCurrentSession() = withAuthOperation { call ->
        val original = call.session ?: error("No session found")
        val newSession = refreshSession(original.refreshToken)
        check(original.user?.id == newSession.user?.id) { "Refresh response changed owner" }
        importSessionChecked(newSession, config.alwaysAutoRefresh, SessionSource.Refresh(original), call.operation)
        Unit
    }

    override suspend fun importSession(
        session: UserSession,
        autoRefresh: Boolean,
        source: SessionSource
    ) {
        val inherited = currentCoroutineContext()[AuthCallContext]?.takeIf { it.auth === this }
        importSessionChecked(session, autoRefresh, source, inherited?.operation ?: sessionAuthority.operation())
    }

    private data class Publication(val operation: AuthOperation, val session: UserSession)

    private suspend fun importSessionChecked(
        session: UserSession,
        autoRefresh: Boolean,
        source: SessionSource,
        operation: AuthOperation,
        beforePersistence: suspend () -> Unit = {},
        guard: () -> Boolean = { true },
    ): Publication {
        sessionAuthority.validate(operation)
        if (autoRefresh && session.expiresAt <= Clock.System.now()) {
            return refreshWithRetry(session, operation, autoRefresh, source, beforePersistence, guard)
                ?: throw AuthAuthorityUnavailableException()
        }
        val ownJob = currentCoroutineContext()[AuthRefreshTask]?.job
        val initiatingFlow = currentCoroutineContext()[AuthExternalContext]?.flow
        var committed: AuthOperation? = null
        sessionAuthority.mutate(operation, session.user?.id, source !is SessionSource.Refresh, ::invalidateFailedSession,
            guard = { guard() && (initiatingFlow == null || (flowIsCurrent(initiatingFlow) && initiatingFlow.call.operation === operation)) },
            onCommitted = { ticket ->
                committed = ticket
                if (initiatingFlow != null) initiatingFlow.completedOperation = ticket
                if (autoRefresh) scheduleRefresh(session, ticket)
        }) {
            beforePersistence()
            if (initiatingFlow != null) consumeExternalFlow(initiatingFlow)
            if (session.refreshToken.isNotBlank() && session.expiresIn != 0L && config.autoSaveToStorage) sessionManager.saveSession(session)
            if (sessionJob !== ownJob) sessionJob?.cancel()
            sessionJob = null
            externalFlow = null
            _sessionStatus.value = SessionStatus.Authenticated(session, source)
        }
        return Publication(requireNotNull(committed), session)
    }

    private fun scheduleRefresh(session: UserSession, operation: AuthOperation) {
        refreshContext = AuthCallContext(this, operation, session)
        if (lifecyclePaused) return
        lateinit var task: Job
        task = authScope.launch(start = CoroutineStart.LAZY) {
            withContext(AuthRefreshTask(task)) {
                try {
                    delayBeforeExpiry(session)
                    refreshWithRetry(session, operation, true, SessionSource.Refresh(session))
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { Auth.logger.e { "Automatic refresh stopped safely; no successor is modified" } }
            }
        }
        sessionJob = task
        task.start()
    }

    private suspend fun refreshWithRetry(
        session: UserSession,
        operation: AuthOperation,
        autoRefresh: Boolean,
        source: SessionSource,
        beforePersistence: suspend () -> Unit = {},
        guard: () -> Boolean = { true },
    ): Publication? = withContext(AuthCallContext(this, operation, session)) {
        val call = requireNotNull(currentCoroutineContext()[AuthCallContext])
        while (true) {
            sessionAuthority.validate(operation)
            val renewed = try {
                refreshSession(session.refreshToken)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: RestException) {
                if (source !is SessionSource.Refresh) throw failure // A failed attempted A login must not clear current B.
                if (call.identityClearedByThisOperation) return@withContext null
                if (failure.statusCode !in 500..599) {
                    clearSessionChecked(operation)
                    return@withContext null
                }
                sessionAuthority.local(operation) {
                    _sessionStatus.value = SessionStatus.RefreshFailure(RefreshFailureCause.InternalServerError(failure))
                }
                null
            } catch (failure: Exception) {
                if (source !is SessionSource.Refresh || (failure !is HttpRequestException && failure !is HttpRequestTimeoutException && failure !is IOException)) throw failure
                sessionAuthority.local(operation) {
                    _sessionStatus.value = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(failure))
                }
                null
            }
            // Never treat a persistence/publication/parsing error as a network retry.
            if (renewed != null) {
                check(renewed.expiresAt > Clock.System.now()) { "Refresh response is already expired" }
                check(session.user?.id == renewed.user?.id) { "Refresh response changed owner" }
                return@withContext importSessionChecked(renewed, autoRefresh, source, operation, beforePersistence, guard)
            }
            delay(config.retryDelay) // cancellation/stale generation never becomes a retry of a successor
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private suspend fun delayBeforeExpiry(session: UserSession) {
        val timeAtBeginningOfSession = session.expiresAt - session.expiresIn.seconds

        // 80% of the way to session.expiresAt
        val targetRefreshTime = timeAtBeginningOfSession + (session.expiresIn.seconds * SESSION_REFRESH_THRESHOLD)

        val delayDuration = targetRefreshTime - Clock.System.now()

        // if the delayDuration is negative, delay() will not delay
        delay(delayDuration)
    }

    override suspend fun startAutoRefreshForCurrentSession() = withAuthOperation { call ->
        val session = call.session ?: error("No session found")
        sessionAuthority.control(call.operation, ::invalidateFailedSession) { next ->
            sessionJob?.cancel()
            scheduleRefresh(session, next)
        }
        Unit
    }

    override suspend fun stopAutoRefreshForCurrentSession() = withAuthOperation { call ->
        sessionAuthority.control(call.operation, ::invalidateFailedSession) {
            sessionJob?.cancel()
            sessionJob = null
            refreshContext = null
        }
        Unit
    }

    override suspend fun loadFromStorage(autoRefresh: Boolean): Boolean = withAuthOperation { call ->
        loadFromStorageChecked(autoRefresh, call.operation)
    }

    private suspend fun loadFromStorageChecked(autoRefresh: Boolean, operation: AuthOperation): Boolean {
        val store = sessionManager as? AtomicSessionStore
        if (store == null) {
            // Read-only compatibility: legacy bytes are not proof of a durable committed session.
            // Still validate the captured SDK operation after a possibly delayed legacy read.
            sessionManager.loadSession()
            sessionAuthority.validate(operation)
            return false
        }
        val recovered = store.readCommitted()
        sessionAuthority.validate(operation)
        if (recovered !is StoreOutcome.VerifiedCommitted) return false
        val session = recovered.session ?: return false
        if (session.expiresAt <= Clock.System.now()) {
            // Never rotate a durable credential without an explicitly enabled refresh + persistence.
            if (!autoRefresh || !config.autoSaveToStorage) return false
            return renewRecoveredSession(store, recovered, operation)
        }
        sessionAuthority.mutate(operation, session.user?.id, true, ::invalidateFailedSession,
            onCommitted = { if (autoRefresh) scheduleRefresh(session, it) }) {
            if (!store.publishIfCurrent(recovered.ticket) {
                sessionJob?.cancel()
                sessionJob = null
                externalFlow = null
                _sessionStatus.value = SessionStatus.Authenticated(session, SessionSource.Storage)
            }) throw AuthStorageUnavailableException()
        }
        return true
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private suspend fun renewRecoveredSession(
        store: AtomicSessionStore,
        recovered: StoreOutcome.VerifiedCommitted,
        operation: AuthOperation,
    ): Boolean {
        val original = requireNotNull(recovered.session)
        sessionAuthority.local(operation) {
            if (!store.publishIfCurrent(recovered.ticket) {}) throw AuthStorageUnavailableException()
        }
        // A single bounded attempt, outside authority/storage locks. No retry/logout/legacy fallback.
        val renewed = withContext(AuthCallContext(this, operation, original)) {
            refreshSession(original.refreshToken)
        }
        check(renewed.user?.id == original.user?.id) { "Recovery refresh changed owner" }
        check(renewed.expiresAt > Clock.System.now()) { "Recovery refresh is already expired" }
        sessionAuthority.mutate(operation, renewed.user?.id, true, ::invalidateFailedSession,
            onCommitted = { scheduleRefresh(renewed, it) }) {
            // Never reread a successor head as the expected predecessor after network suspension.
            val prepared = store.prepare(recovered.ticket.head, kotlin.uuid.Uuid.random().toString(), renewed) as? StoreOutcome.Prepared
                ?: throw AuthStorageUnavailableException()
            if (store.verifyPrepared(prepared.ticket) !is StoreOutcome.Prepared) throw AuthStorageUnavailableException()
            val committed = store.commitIfCurrent(prepared.ticket) as? StoreOutcome.VerifiedCommitted
                ?: throw AuthStorageUnavailableException()
            if (!store.publishIfCurrent(committed.ticket) {
                sessionJob?.cancel()
                sessionJob = null
                externalFlow = null
                _sessionStatus.value = SessionStatus.Authenticated(renewed, SessionSource.Storage)
            }) throw AuthStorageUnavailableException()
        }
        return true
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            sessionAuthority.shutdown {
                authScope.cancel()
                sessionJob?.cancel()
                sessionJob = null
                externalFlow = null
                _sessionStatus.value = SessionStatus.NotAuthenticated(false)
                refreshContext = null
            }
            // Observer removal dispatches to Main; never wait for it inside the session fence.
            platformLifecycleJob?.join()
        }
    }

    override suspend fun parseErrorResponse(response: HttpResponse): RestException {
        val errorBody =
            response.bodyOrNull<GoTrueErrorResponse>() ?: GoTrueErrorResponse("Unknown error", "")
        val call = response.call.request.attributes.getOrNull(AuthRequestContext)?.takeIf { it.auth === this }
        val originatingOperation = call?.takeIf {
            it.session != null && response.call.request.headers[HttpHeaders.Authorization] == "Bearer ${it.session.accessToken}"
        }?.operation
        checkErrorCodes(errorBody, response, originatingOperation)?.let { return it }
        return when (response.status) {
            HttpStatusCode.Unauthorized -> UnauthorizedRestException(
                errorBody.error ?: "Unauthorized",
                response,
                errorBody.description
            )
            HttpStatusCode.BadRequest -> BadRequestRestException(
                errorBody.error ?: "Bad Request",
                response,
                errorBody.description
            )
            HttpStatusCode.UnprocessableEntity -> BadRequestRestException(
                errorBody.error ?: "Unprocessable Entity",
                response,
                errorBody.description
            )
            else -> UnknownRestException(errorBody.error ?: "Unknown Error", response)
        }
    }

    private suspend fun checkErrorCodes(error: GoTrueErrorResponse, response: HttpResponse, operation: AuthOperation?): RestException? {
        return when (error.error) {
            AuthWeakPasswordException.CODE -> AuthWeakPasswordException(error.description, response.status.value, error.weakPassword?.reasons ?: emptyList())
            AuthSessionMissingException.CODE -> {
                if (operation != null) {
                    try { clearSessionChecked(operation) } catch (_: StaleAuthOperationException) { /* Never clear successor. */ }
                }
                AuthSessionMissingException(response.status.value)
            }
            else -> {
                error.error?.let { AuthRestException(it, error.description, response.status.value) }
            }
        }
    }

    @OptIn(SupabaseExperimental::class)
    override suspend fun getOAuthUrl(
        provider: OAuthProvider,
        redirectUrl: String?,
        url: String,
        flow: AuthExternalFlow?,
        additionalConfig: ExternalAuthConfigDefaults.() -> Unit
    ): String {
        val active = requireExternalFlow(flow)
        return withContext(active.call) {
            val options = ExternalAuthConfigDefaults().apply(additionalConfig)
            val target = externalRedirect(redirectUrl, active)
            val purpose = if (url == "user/identities/authorize") AuthFlowPurpose.LINK else AuthFlowPurpose.OAUTH
            if (purpose == AuthFlowPurpose.LINK && active.call.session?.user?.id == null) throw UnverifiedAuthFlowException(AuthFlowRejection.OWNER_MISMATCH)
            val challenge = preparePKCEIfEnabled(active, purpose)
            val result = URLBuilder(resolveUrl(url)).apply {
                val reserved = setOf("provider", "redirect_to", "state", "scopes", "code_challenge", "code_challenge_method")
                options.queryParams.filterKeys { it !in reserved }.forEach { (key, value) -> parameters.append(key, value) }
                parameters["provider"] = provider.name
                parameters["redirect_to"] = target
                parameters["state"] = active.state
                if (options.scopes.isNotEmpty()) parameters["scopes"] = options.scopes.joinToString(" ")
                challenge?.let {
                    parameters["code_challenge"] = it
                    parameters["code_challenge_method"] = PKCEConstants.CHALLENGE_METHOD
                }
            }.buildString()
            sessionAuthority.local(active.call.operation) {
                ensureCurrentFlow(active)
                result
            }
        }
    }

    override suspend fun clearSession() {
        val inherited = currentCoroutineContext()[AuthCallContext]?.takeIf { it.auth === this }
        clearSessionChecked(inherited?.operation ?: sessionAuthority.operation())
    }

    private suspend fun clearSessionChecked(operation: AuthOperation) {
        val ownJob = currentCoroutineContext()[AuthRefreshTask]?.job
        sessionAuthority.mutate(operation, null, true, ::invalidateFailedSession) {
            codeVerifierCache.deleteCodeVerifier()
            sessionManager.deleteSession()
            if (sessionJob !== ownJob) sessionJob?.cancel()
            _sessionStatus.value = SessionStatus.NotAuthenticated(true)
            sessionJob = null
            refreshContext = null
            externalFlow = null
        }
        currentCoroutineContext()[AuthCallContext]?.takeIf {
            it.auth === this && it.operation === operation
        }?.identityClearedByThisOperation = true
    }

    private fun invalidateFailedSession(failure: Throwable) {
        // Inline under authority: never clear a successor from an asynchronous catch/collector.
        sessionJob?.cancel()
        sessionJob = null
        refreshContext = null
        externalFlow = null
        _sessionStatus.value = SessionStatus.NotAuthenticated(false)
    }

    override suspend fun awaitInitialization() {
        sessionStatus.first { it !is SessionStatus.Initializing }
    }

    suspend fun resetLoadingState() = lifecycleBackground(sessionAuthority.operation())

    internal suspend fun lifecycleBackground(expected: AuthOperation) {
        applyLifecycle(expected, foreground = false)
    }

    internal suspend fun lifecycleForeground(expected: AuthOperation) {
        applyLifecycle(expected, foreground = true)
    }

    private suspend fun applyLifecycle(expected: AuthOperation, foreground: Boolean) {
        // Bootstrap must finish its own restore/no-session publication. Do not retire its ticket.
        val initializing = if (_sessionStatus.value is SessionStatus.Initializing) sessionAuthority.local(expected) {
            if (_sessionStatus.value is SessionStatus.Initializing) {
                lifecyclePaused = !foreground
                true
            } else false
        } else false
        if (initializing) return
        sessionAuthority.lifecycleControl(expected, ::invalidateFailedSession) { previous, next ->
            lifecyclePaused = !foreground
            sessionJob?.cancel()
            sessionJob = null
            val retained = refreshContext?.takeIf {
                it.operation.identity === previous.identity && it.operation.generation == previous.generation
            }?.session
            val session = currentSessionOrNull() ?: retained
            refreshContext = session?.let { AuthCallContext(this, next, it) }
            externalFlow?.let { flow ->
                val original = flow.call
                if (!flow.inFlight && original.operation.authority === previous.authority &&
                    original.operation.identity === previous.identity && original.operation.generation == previous.generation) {
                    // Only the exact lifecycle receipt carries the original immutable credential.
                    flow.call = AuthCallContext(this, next, original.session)
                } else externalFlow = null
            }
            if (foreground && config.alwaysAutoRefresh && session != null) scheduleRefresh(session, next)
        }
    }

    internal suspend fun beginExternalFlow(): AuthExternalFlow = withAuthOperation { call ->
        val flow = AuthExternalFlow(call, generateCodeVerifier(), config.flowType)
        sessionAuthority.local(call.operation) { externalFlow = flow }
        flow
    }

    private fun flowIsCurrent(flow: AuthExternalFlow): Boolean = flow.call.auth === this && externalFlow === flow

    private fun ensureCurrentFlow(flow: AuthExternalFlow) {
        if (!flowIsCurrent(flow)) throw StaleAuthOperationException()
        if (flow.flowType != config.flowType) throw UnverifiedAuthFlowException(AuthFlowRejection.FLOW_TYPE_MISMATCH)
    }

    private suspend fun requireExternalFlow(explicit: AuthExternalFlow? = null): AuthExternalFlow {
        val flow = explicit ?: currentCoroutineContext()[AuthExternalContext]?.flow
            ?: throw UnverifiedAuthFlowException(AuthFlowRejection.MISSING_PROVENANCE)
        if (flow.call.auth !== this) throw StaleAuthOperationException()
        sessionAuthority.local(flow.call.operation) { ensureCurrentFlow(flow) }
        return flow
    }

    internal suspend fun <T> initiateExternalFlow(flow: AuthExternalFlow, initiate: suspend () -> T): AuthExternalInitiation<T> {
        requireExternalFlow(flow)
        return withContext(flow.call + AuthExternalContext(flow)) {
            try {
                val result = initiate()
                val completion = flow.completedOperation ?: flow.call.operation
                sessionAuthority.local(completion) {
                    if (externalFlow !== flow && !(externalFlow == null && flow.completedOperation === completion)) throw StaleAuthOperationException()
                    AuthExternalInitiation(flow, result)
                }
            } catch (failure: Throwable) {
                // Retire this ephemeral attempt, not committed authentication or durable bytes.
                withContext(NonCancellable) {
                    try { sessionAuthority.local(flow.call.operation) { if (flowIsCurrent(flow)) externalFlow = null } }
                    catch (_: StaleAuthOperationException) { /* Cannot retire a successor's flow. */ }
                }
                throw failure
            }
        }
    }

    internal suspend fun externalRedirect(redirectUrl: String?, explicit: AuthExternalFlow? = null): String {
        val flow = requireExternalFlow(explicit)
        val redirect = redirectUrl ?: defaultRedirectUrl() ?: throw UnverifiedAuthFlowException(AuthFlowRejection.INVALID_REDIRECT)
        val target = try { URLBuilder(redirect) } catch (_: Exception) { throw UnverifiedAuthFlowException(AuthFlowRejection.INVALID_REDIRECT) }
        if (!redirect.contains("://") || target.host.isBlank() || target.fragment.isNotEmpty() ||
            (target.protocol.name !in setOf("http", "https") && (target.protocol.name != config.scheme || target.host != config.host))) {
            throw UnverifiedAuthFlowException(AuthFlowRejection.INVALID_REDIRECT)
        }
        if (target.parameters.contains("state")) throw UnverifiedAuthFlowException(AuthFlowRejection.STATE_MISMATCH)
        target.parameters.append("state", flow.state)
        return target.buildString()
    }

    /** The platform caller supplies only its bounded local browser-launch effect, never HTTP. */
    internal suspend fun publishExternalAction(action: suspend () -> Unit) {
        val flow = requireExternalFlow()
        sessionAuthority.local(flow.call.operation) {
            ensureCurrentFlow(flow)
            if (!flow.prepared) throw UnverifiedAuthFlowException(AuthFlowRejection.NOT_PREPARED)
            action()
        }
    }

    private suspend fun claimCallback(flow: AuthExternalFlow, state: String?, expectedType: FlowType, operation: AuthOperation) {
        sessionAuthority.local(operation) {
            ensureCurrentFlow(flow)
            if (flow.call.operation !== operation) throw StaleAuthOperationException()
            if (!flow.prepared || flow.purpose == null) throw UnverifiedAuthFlowException(AuthFlowRejection.NOT_PREPARED)
            if (flow.flowType != expectedType) throw UnverifiedAuthFlowException(AuthFlowRejection.FLOW_TYPE_MISMATCH)
            if (state != flow.state) throw UnverifiedAuthFlowException(AuthFlowRejection.STATE_MISMATCH)
            if (flow.inFlight) throw UnverifiedAuthFlowException(AuthFlowRejection.ALREADY_IN_FLIGHT)
            if (expectedType == FlowType.PKCE && (flow.verifier == null || codeVerifierCache.loadCodeVerifier() != flow.verifier)) {
                throw UnverifiedAuthFlowException(AuthFlowRejection.VERIFIER_MISMATCH)
            }
            flow.inFlight = true
        }
    }

    private suspend fun releaseCallback(flow: AuthExternalFlow, operation: AuthOperation) = withContext(NonCancellable) {
        try { sessionAuthority.local(operation) { if (flowIsCurrent(flow)) flow.inFlight = false } }
        catch (_: StaleAuthOperationException) { /* Never touch a successor. */ }
    }

    private fun checkCallbackOwner(flow: AuthExternalFlow, session: UserSession) {
        if (flow.purpose in setOf(AuthFlowPurpose.LINK, AuthFlowPurpose.EMAIL_UPDATE) &&
            flow.call.session?.user?.id != session.user?.id) throw UnverifiedAuthFlowException(AuthFlowRejection.OWNER_MISMATCH)
    }

    internal suspend fun importExternalFragment(flow: AuthExternalFlow, fragment: String, callback: (UserSession) -> Unit) {
        val call = flow.call
        withContext(call + AuthExternalContext(null)) {
            val states = fragment.split('&').filter { it.substringBefore('=') == "state" }
            if (states != listOf("state=${flow.state}")) throw UnverifiedAuthFlowException(AuthFlowRejection.STATE_MISMATCH)
            val operation = call.operation
            claimCallback(flow, flow.state, FlowType.IMPLICIT, operation)
            try {
                val parsed = parseSessionFromFragment(fragment)
                val user = retrieveUser(parsed.accessToken)
                val session = parsed.copy(user = user)
                checkCallbackOwner(flow, session)
                val published = importSessionChecked(session, config.alwaysAutoRefresh, SessionSource.External, operation,
                    beforePersistence = { consumeExternalFlow(flow) }, guard = { flowIsCurrent(flow) && flow.flowType == config.flowType })
                sessionAuthority.local(published.operation) { callback(published.session) }
            } finally { releaseCallback(flow, operation) }
        }
    }

    internal suspend fun importExternalCode(flow: AuthExternalFlow, state: String?, code: String, callback: (UserSession) -> Unit) {
        val published = exchangeExternalCode(flow, state, code, true)
        sessionAuthority.local(published.operation) { callback(published.session) }
    }

    private suspend fun exchangeExternalCode(flow: AuthExternalFlow, state: String?, code: String, saveSession: Boolean): Publication {
        val call = flow.call
        return withContext(call + AuthExternalContext(null)) {
            if (code.isBlank()) throw UnverifiedAuthFlowException(AuthFlowRejection.NOT_PREPARED)
            val operation = call.operation
            claimCallback(flow, state, FlowType.PKCE, operation)
            try {
                val verifier = requireNotNull(flow.verifier)
                val session = api.postJson("token?grant_type=pkce", buildJsonObject {
                    put("auth_code", code); put("code_verifier", verifier)
                }) { headers.remove("Authorization") }.safeBody<UserSession>()
                checkCallbackOwner(flow, session)
                if (saveSession) importSessionChecked(session, config.alwaysAutoRefresh, SessionSource.External, operation,
                    beforePersistence = { consumeExternalFlow(flow) }, guard = { flowIsCurrent(flow) && flow.flowType == config.flowType })
                else sessionAuthority.local(operation) {
                    consumeExternalFlow(flow)
                    Publication(operation, session)
                }
            } finally { releaseCallback(flow, operation) }
        }
    }

    private suspend fun consumeExternalFlow(flow: AuthExternalFlow) {
        check(flowIsCurrent(flow)) { "Unverified external flow" }
        if (flow.verifier != null) {
            check(codeVerifierCache.loadCodeVerifier() == flow.verifier) { "PKCE verifier changed" }
            codeVerifierCache.deleteCodeVerifier()
        }
        externalFlow = null
    }

    /**
     * Prepares PKCE if enabled and returns the code challenge.
     */
    private suspend fun preparePKCEIfEnabled(flow: AuthExternalFlow, purpose: AuthFlowPurpose): String? {
        val active = requireExternalFlow(flow)
        val codeVerifier = generateCodeVerifier()
        sessionAuthority.local(active.call.operation) {
            ensureCurrentFlow(active)
            check(!active.prepared) { "External flow was already prepared" }
            if (active.flowType == FlowType.PKCE) {
                codeVerifierCache.saveCodeVerifier(codeVerifier)
                active.verifier = codeVerifier
            }
            active.purpose = purpose
            active.prepared = true
        }
        return if (active.flowType == FlowType.PKCE) generateCodeChallenge(codeVerifier) else null
    }

}

@SupabaseInternal
expect fun Auth.setupPlatform()

@SupabaseInternal
expect fun Auth.createDefaultSessionManager(): SessionManager

@SupabaseInternal
expect fun Auth.createDefaultCodeVerifierCache(): CodeVerifierCache
