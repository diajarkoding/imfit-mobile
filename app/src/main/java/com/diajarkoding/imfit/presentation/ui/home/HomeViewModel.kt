package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.avatarPresentationUrl
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import com.diajarkoding.imfit.data.remote.online.*

data class HomeState(
    val userName: String = "User",
    val userProfilePhotoUri: String? = null,
    val templates: List<WorkoutTemplate> = emptyList(),
    val lastWorkout: WorkoutLog? = null,
    val isLoading: Boolean = true,
    val newlyCreatedWorkoutId: String? = null,
    val activeWorkoutTemplateId: String? = null,
    val isCreating: Boolean = false,
    @StringRes val createErrorRes: Int? = null,
    @StringRes val createNameErrorRes: Int? = null,
    val retryingUploads: Boolean = false,
    val uploadRetryFailed: Boolean = false,
    val templateSummaries: List<TemplateSummary> = emptyList(),
    val remoteLastWorkout: WorkoutSummary? = null,
    val homeLoaded: Boolean = false,
    val hasReadError: Boolean = false,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val appending: Boolean = false,
    val appendError: Boolean = false,
    val endReached: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val workoutRepository: WorkoutRepository,

    private val reads: RemoteReadGateway,
    private val edits: TemplateEditBridge,
    private val changes: OnlineMutationChanges,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    
    private var loadJob: Job? = null
    private var displayedOwner: String? = null
    private val templates = reads.templateFeed(viewModelScope)
    private val profile = RemoteResource<RemoteProfile>(reads.auth, viewModelScope)
    private val summary = RemoteResource<HomeSummary>(reads.auth, viewModelScope)
    private val ownerListener = reads.auth.onIdentityChanged { loadJob?.cancel(); _state.value = HomeState() }

    init {
        viewModelScope.launch { combine(templates.state, profile.state, summary.state) { page, user, home ->
            Triple(page, user, home)
        }.collect { (page, user, home) ->
            reads.auth.synchronizedState {
            val identity = reads.auth.identity.value ?: return@synchronizedState
            if (page.identity != identity || user.identity != identity || home.identity != identity) return@synchronizedState
            _state.update { it.copy(userName = user.data?.name.orEmpty(), userProfilePhotoUri = user.data?.avatarUrl,
                templateSummaries = page.items, remoteLastWorkout = home.data?.lastWorkout, homeLoaded = home.data != null,
                hasReadError = page.refreshError != null || home.error != null || user.error != null,
                isLoading = (!page.hasLoaded && page.refreshing) || (home.data == null && home.loading),
                refreshing = page.refreshing || home.loading || user.loading, stale = page.stale || home.stale || user.stale,
                appending = page.appending, appendError = page.appendError != null, endReached = page.endReached) }
            }
        } }
        viewModelScope.launch { reads.auth.identity.collectLatest { if (it != null) loadData() } }
        viewModelScope.launch { edits.changes.collect { if (reads.auth.isCurrent(it.identity)) templates.refresh() } }
        viewModelScope.launch { changes.events.collect { change ->
            reads.auth.synchronizedState {
                if (!reads.auth.isCurrent(change.identity)) return@synchronizedState
                when (change) {
                    is WorkoutCommitted -> {
                        summary.load("home", true) { reads.read(OnlineRpc.homeSummary()) }
                        loadSessionIndication(change.identity)
                    }
                    is ProfileChanged -> profile.load("profile", true) { loadProfile() }
                }
            }
        } }
        runCatching { reads.auth.capture() }
    }

    private fun loadData() {
        if (reads.auth.identity.value == null) return
        templates.select(QueryKey.Templates)
        profile.load("profile") { loadProfile() }
        summary.load("home") { reads.read(OnlineRpc.homeSummary()) }
        loadSessionIndication(reads.auth.identity.value ?: return)
    }

    private fun loadSessionIndication(identity: AuthIdentity) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                reads.auth.withCurrent(identity) { }
                val session = workoutRepository.getActiveSession()
                reads.auth.synchronizedState {
                    if (reads.auth.isCurrent(identity)) _state.update { it.copy(activeWorkoutTemplateId = session?.templateId) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An unavailable read is not authoritative absence. */ }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun refresh() {
        templates.refresh()
        profile.load("profile", true) { loadProfile() }
        summary.load("home", true) { reads.read(OnlineRpc.homeSummary()) }
    }


    fun createWorkout(name: String) {
        if (_state.value.isCreating || _state.value.newlyCreatedWorkoutId != null) return
        if (name.isBlank()) { _state.update { it.copy(createNameErrorRes = R.string.error_template_name_required) }; return }
        _state.update { it.copy(isCreating = true, createErrorRes = null, createNameErrorRes = null) }
        viewModelScope.launch {
            try {
            val user = reads.auth.identity.value
            if (user == null) {
                _state.update { it.copy(isCreating = false, createErrorRes = R.string.routine_create_failed) }
                return@launch
            }

                val receipt = edits.save(null, name.trim(), emptyList())
                _state.update { it.copy(newlyCreatedWorkoutId = receipt.templateId, isCreating = false) }
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
    fun retryPage() = templates.retry()
    fun access(id: String) { templates.state.value.items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(templates::access) }
    fun more() { templates.state.value.items.lastOrNull()?.let { access(it.id) } }
    private suspend fun loadProfile(): RemoteProfile {
        val profile = reads.profile()
        return profile.copy(avatarUrl = authRepository.avatarPresentationUrl(profile.avatarUrl))
    }
    override fun onCleared() { loadJob?.cancel(); templates.close(); profile.close(); summary.close(); ownerListener.close() }
}
