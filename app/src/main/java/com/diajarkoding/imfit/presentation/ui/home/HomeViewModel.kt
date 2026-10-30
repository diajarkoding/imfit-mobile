package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
enum class HomeRefreshTrigger {
    INITIAL,
    AUTO,
    MANUAL
}

data class HomeState(
    val userName: String = "User",
    val userProfilePhotoUri: String? = null,
    val isProfileLoading: Boolean = true,
    val isPullRefreshing: Boolean = false,
    val lastWorkoutState: SectionLoadState<WorkoutLog?> = SectionLoadState.Loading,
    val templatesState: SectionLoadState<List<WorkoutTemplate>> = SectionLoadState.Loading,
    val newlyCreatedWorkoutId: String? = null,
    val activeWorkoutTemplateId: String? = null,
    val isCreating: Boolean = false,
    @StringRes val createErrorRes: Int? = null,
    @StringRes val createNameErrorRes: Int? = null
) {
    val templates: List<WorkoutTemplate>
        get() = when (val s = templatesState) {
            is SectionLoadState.Success -> s.data
            is SectionLoadState.Error -> s.previousData.orEmpty()
            is SectionLoadState.Loading -> emptyList()
        }

    val lastWorkout: WorkoutLog?
        get() = when (val s = lastWorkoutState) {
            is SectionLoadState.Success -> s.data
            is SectionLoadState.Error -> s.previousData
            is SectionLoadState.Loading -> null
        }

    val isLastWorkoutLoading: Boolean
        get() = lastWorkoutState is SectionLoadState.Loading

    val isTemplatesLoading: Boolean
        get() = templatesState is SectionLoadState.Loading

    val isLoading: Boolean
        get() = isProfileLoading || isLastWorkoutLoading || isTemplatesLoading
}

@HiltViewModel
class HomeViewModel(
    private val authRepository: AuthRepository,
    private val workoutRepository: WorkoutRepository,
    private val overrideScope: CoroutineScope? = null
) : ViewModel() {

    @Inject
    constructor(
        authRepository: AuthRepository,
        workoutRepository: WorkoutRepository
    ) : this(
        authRepository = authRepository,
        workoutRepository = workoutRepository,
        overrideScope = null
    )

    private val scope: CoroutineScope
        get() = overrideScope ?: viewModelScope

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var localObserverJob: Job? = null
    private val requestGeneration = AtomicLong(0L)
    private var currentUserId: String? = null
    private var hasHandledInitialEntry = false

    init {
        refreshHome(HomeRefreshTrigger.INITIAL)
        observeProfile()
    }
    private fun observeProfile() {
        scope.launch {
            authRepository.userProfileState.collect { profile ->
                val resolvedName = when {
                    !profile.hasRetainedSessionContext && !profile.isProfileLoading -> "Guest"
                    profile.displayName.isNotBlank() -> profile.displayName
                    else -> "User"
                }
                _state.update {
                    it.copy(
                        userName = resolvedName,
                        userProfilePhotoUri = profile.resolvedAvatarUrl,
                        isProfileLoading = profile.isProfileLoading
                    )
                }
                val nextUserId = profile.userId
                when {
                    !profile.hasRetainedSessionContext && !profile.isProfileLoading -> {
                        if (currentUserId != null) {
                            loadJob?.cancel()
                            localObserverJob?.cancel()
                            requestGeneration.incrementAndGet()
                            currentUserId = null
                            _state.update {
                                it.copy(
                                    userName = "Guest",
                                    userProfilePhotoUri = null,
                                    isProfileLoading = false,
                                    isPullRefreshing = false,
                                    lastWorkoutState = SectionLoadState.Success(null),
                                    templatesState = SectionLoadState.Success(emptyList()),
                                    activeWorkoutTemplateId = null
                                )
                            }
                        }
                    }
                    nextUserId != null && nextUserId != currentUserId -> {
                        currentUserId = nextUserId
                        _state.update {
                            it.copy(
                                isPullRefreshing = false,
                                lastWorkoutState = SectionLoadState.Loading,
                                templatesState = SectionLoadState.Loading,
                                activeWorkoutTemplateId = null
                            )
                        }
                        executeRefreshPipeline(nextUserId, HomeRefreshTrigger.INITIAL)
                    }
                }
            }
        }
    }

    fun refreshHome(trigger: HomeRefreshTrigger = HomeRefreshTrigger.AUTO) {
        if (trigger == HomeRefreshTrigger.MANUAL &&
            _state.value.isPullRefreshing &&
            loadJob?.isActive == true
        ) {
            return
        }
        val knownUserId = currentUserId
            ?: authRepository.userProfileState.value.userId
        if (knownUserId != null) {
            val isNewUser = currentUserId != knownUserId
            currentUserId = knownUserId
            executeRefreshPipeline(knownUserId, trigger, isNewUser = isNewUser)
            return
        }

        val gen = requestGeneration.incrementAndGet()
        loadJob?.cancel()
        localObserverJob?.cancel()
        if (trigger == HomeRefreshTrigger.MANUAL) {
            _state.update { it.copy(isPullRefreshing = true) }
        }
        loadJob = scope.launch {
            try {
                try {
                    authRepository.refreshProfile(force = trigger == HomeRefreshTrigger.MANUAL)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
                val profile = authRepository.userProfileState.value
                val resolvedUserId = profile.userId ?: authRepository.getLocalUserId()
                if (resolvedUserId == null) {
                    if (!profile.isProfileLoading && requestGeneration.get() == gen) {
                        currentUserId = null
                        _state.update {
                            it.copy(
                                userName = "Guest",
                                userProfilePhotoUri = null,
                                isProfileLoading = false,
                                isPullRefreshing = false,
                                lastWorkoutState = SectionLoadState.Success(null),
                                templatesState = SectionLoadState.Success(emptyList()),
                                activeWorkoutTemplateId = null
                            )
                        }
                    }
                    return@launch
                }
                if (requestGeneration.get() != gen) return@launch
                val isNewUser = currentUserId != resolvedUserId
                currentUserId = resolvedUserId
                _state.update { current ->
                    current.copy(
                        lastWorkoutState = if (isNewUser) SectionLoadState.Loading else current.lastWorkoutState.toRefreshingState(),
                        templatesState = if (isNewUser) SectionLoadState.Loading else current.templatesState.toRefreshingState()
                    )
                }
                runSectionsSupervisor(resolvedUserId, gen, trigger, refreshProfileInScope = false)
                startLocalMutationObservers(resolvedUserId, gen)
            } finally {
                if (requestGeneration.get() == gen) {
                    _state.update { it.copy(isPullRefreshing = false) }
                }
            }
        }
    }

    private fun executeRefreshPipeline(
        userId: String,
        trigger: HomeRefreshTrigger,
        isNewUser: Boolean = false
    ) {
        val gen = requestGeneration.incrementAndGet()
        loadJob?.cancel()
        localObserverJob?.cancel()
        _state.update { current ->
            current.copy(
                isPullRefreshing = trigger == HomeRefreshTrigger.MANUAL,
                lastWorkoutState = if (isNewUser || trigger == HomeRefreshTrigger.INITIAL) {
                    SectionLoadState.Loading
                } else {
                    current.lastWorkoutState.toRefreshingState()
                },
                templatesState = if (isNewUser || trigger == HomeRefreshTrigger.INITIAL) {
                    SectionLoadState.Loading
                } else {
                    current.templatesState.toRefreshingState()
                }
            )
        }
        loadJob = scope.launch {
            try {
                runSectionsSupervisor(userId, gen, trigger, refreshProfileInScope = true)
                startLocalMutationObservers(userId, gen)
            } finally {
                if (requestGeneration.get() == gen) {
                    _state.update { it.copy(isPullRefreshing = false) }
                }
            }
        }
    }

    private suspend fun runSectionsSupervisor(
        userId: String,
        gen: Long,
        trigger: HomeRefreshTrigger,
        refreshProfileInScope: Boolean
    ) {
        supervisorScope {
            if (refreshProfileInScope) {
                launch {
                    try {
                        val result = authRepository.refreshProfile(
                            force = trigger == HomeRefreshTrigger.MANUAL
                        )
                        result.exceptionOrNull()?.let { err ->
                            runCatching { Log.w("HomeViewModel", "Profile refresh failed: ${err.message}") }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        runCatching { Log.w("HomeViewModel", "Profile refresh threw: ${t.message}") }
                    }
                }
            }

            launch {
                try {
                    val active = workoutRepository.getActiveSession()
                    ensureActive()
                    if (!isValidRequest(userId, gen)) return@launch
                    _state.update { it.copy(activeWorkoutTemplateId = active?.templateId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }

            launch {
                try {
                    val result = workoutRepository.fetchRemoteLastWorkoutLog(userId)
                    ensureActive()
                    if (!isValidRequest(userId, gen)) return@launch
                    _state.update { current ->
                        current.copy(
                            lastWorkoutState = SectionLoadState.Success(
                                data = result.data,
                                isRefreshing = false,
                                isFromRemote = result.isFromRemote
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (!isValidRequest(userId, gen)) return@launch
                    runCatching { Log.e("HomeViewModel", "Failed to load last workout for Home", t) }
                    _state.update { current ->
                        val prev = current.lastWorkoutState
                        val (prevData, hasPrev) = when (prev) {
                            is SectionLoadState.Success -> prev.data to true
                            is SectionLoadState.Error -> prev.previousData to prev.hasPreviousData
                            is SectionLoadState.Loading -> null to false
                        }
                        current.copy(
                            lastWorkoutState = SectionLoadState.Error(
                                errorRes = R.string.redesign_error,
                                message = t.message,
                                previousData = prevData,
                                hasPreviousData = hasPrev
                            )
                        )
                    }
                }
            }

            launch {
                try {
                    val result = workoutRepository.fetchRemoteTemplates(userId)
                    ensureActive()
                    if (!isValidRequest(userId, gen)) return@launch
                    _state.update { current ->
                        current.copy(
                            templatesState = SectionLoadState.Success(
                                data = result.data,
                                isRefreshing = false,
                                isFromRemote = result.isFromRemote
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (!isValidRequest(userId, gen)) return@launch
                    runCatching { Log.e("HomeViewModel", "Failed to load templates for Home", t) }
                    _state.update { current ->
                        val prev = current.templatesState
                        val (prevData, hasPrev) = when (prev) {
                            is SectionLoadState.Success -> prev.data to true
                            is SectionLoadState.Error -> prev.previousData to prev.hasPreviousData
                            is SectionLoadState.Loading -> null to false
                        }
                        current.copy(
                            templatesState = SectionLoadState.Error(
                                errorRes = R.string.redesign_error,
                                message = t.message,
                                previousData = prevData,
                                hasPreviousData = hasPrev
                            )
                        )
                    }
                }
            }
        }
    }

    private fun startLocalMutationObservers(userId: String, gen: Long) {
        if (!isValidRequestImmediate(userId, gen)) return
        localObserverJob?.cancel()
        localObserverJob = scope.launch {
            supervisorScope {
                launch {
                    try {
                        workoutRepository.observeLastWorkoutLog(userId).drop(1).collect { updatedLog ->
                            ensureActive()
                            if (!isValidRequest(userId, gen)) return@collect
                            val active = runCatching { workoutRepository.getActiveSession() }.getOrNull()
                            _state.update { current ->
                                val isRemote = (current.lastWorkoutState as? SectionLoadState.Success)?.isFromRemote ?: true
                                current.copy(
                                    activeWorkoutTemplateId = active?.templateId ?: current.activeWorkoutTemplateId,
                                    lastWorkoutState = SectionLoadState.Success(
                                        data = updatedLog,
                                        isRefreshing = false,
                                        isFromRemote = isRemote
                                    )
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
                launch {
                    try {
                        workoutRepository.observeTemplates(userId).drop(1).collect { updatedTemplates ->
                            ensureActive()
                            if (!isValidRequest(userId, gen)) return@collect
                            val active = runCatching { workoutRepository.getActiveSession() }.getOrNull()
                            _state.update { current ->
                                val isRemote = (current.templatesState as? SectionLoadState.Success)?.isFromRemote ?: true
                                current.copy(
                                    activeWorkoutTemplateId = active?.templateId ?: current.activeWorkoutTemplateId,
                                    templatesState = SectionLoadState.Success(
                                        data = updatedTemplates,
                                        isRefreshing = false,
                                        isFromRemote = isRemote
                                    )
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun isValidRequestImmediate(expectedUserId: String, expectedGen: Long): Boolean =
        requestGeneration.get() == expectedGen && currentUserId == expectedUserId

    private suspend fun isValidRequest(expectedUserId: String, expectedGen: Long): Boolean {
        if (requestGeneration.get() != expectedGen) return false
        if (currentUserId != expectedUserId) return false
        val localId = authRepository.peekLocalUserId() ?: authRepository.userProfileState.value.userId
        return localId == expectedUserId
    }

    fun logout() {
        scope.launch {
            authRepository.logout()
        }
    }

    fun refreshOnEntry() {
        if (!hasHandledInitialEntry) {
            hasHandledInitialEntry = true
            return
        }
        refreshHome(HomeRefreshTrigger.AUTO)
    }

    fun pullToRefresh() {
        refreshHome(HomeRefreshTrigger.MANUAL)
    }

    fun refresh() {
        refreshHome(HomeRefreshTrigger.MANUAL)
    }

    fun createWorkout(name: String) {
        if (_state.value.isCreating || _state.value.newlyCreatedWorkoutId != null) return
        if (name.isBlank()) { _state.update { it.copy(createNameErrorRes = R.string.error_template_name_required) }; return }
        _state.update { it.copy(isCreating = true, createErrorRes = null, createNameErrorRes = null) }
        scope.launch {
            try {
            val user = authRepository.getCurrentUser()
            if (user == null) {
                _state.update { it.copy(isCreating = false, createErrorRes = R.string.routine_create_failed) }
                return@launch
            }

                val newWorkout = workoutRepository.createTemplate(
                    userId = user.id,
                    name = name.trim(),
                    exercises = emptyList()
                )
                _state.update { it.copy(newlyCreatedWorkoutId = newWorkout.id, isCreating = false) }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(isCreating = false, createErrorRes = R.string.routine_create_failed) }
                Log.e("HomeViewModel", "Failed to create workout: ${e.message}", e)
            }
        }
    }

    fun clearCreateError() { _state.update { it.copy(createErrorRes = null, createNameErrorRes = null) } }

    fun clearNewlyCreatedWorkoutId() {
        _state.update { it.copy(newlyCreatedWorkoutId = null) }
    }
}
