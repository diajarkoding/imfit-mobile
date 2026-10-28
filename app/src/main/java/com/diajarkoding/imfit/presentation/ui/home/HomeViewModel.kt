package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.data.sync.SyncStateProvider
import com.diajarkoding.imfit.domain.repository.AuthRepository
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
    syncStateProvider: SyncStateProvider,
    private val syncRunner: com.diajarkoding.imfit.data.sync.SyncRunner,
    private val reads: RemoteReadGateway,
    private val edits: TemplateEditBridge,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    
    // Expose sync state for UI
    val syncState = syncStateProvider.syncState
    private var loadJob: Job? = null
    private var displayedOwner: String? = null
    private val templates = reads.templateFeed(viewModelScope)
    private val profile = RemoteResource<RemoteProfile>(reads.auth, viewModelScope)
    private val summary = RemoteResource<HomeSummary>(reads.auth, viewModelScope)
    private val ownerListener = reads.auth.onIdentityChanged { _state.value = HomeState() }

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
        runCatching { reads.auth.capture() }
    }

    private fun loadData() {
        if (reads.auth.identity.value == null) return
        templates.select(QueryKey.Templates)
        profile.load("profile") { loadProfile() }
        summary.load("home") { reads.read(OnlineRpc.homeSummary()) }
        // Explicitly retained legacy recovery ONLY; not the source for templates/history/profile.
        viewModelScope.launch {
            val identity = reads.auth.capture().identity
            try {
                val legacy = workoutRepository.getActiveSession()
                if (reads.auth.isCurrent(identity)) _state.update { it.copy(activeWorkoutTemplateId = legacy?.templateId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Recovery remains unavailable, never fabricate a server session. */ }
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

    fun retryPendingUploads() {
        if (_state.value.retryingUploads) return
        _state.update { it.copy(retryingUploads = true, uploadRetryFailed = false) }
        viewModelScope.launch {
            try {
                val owner = authRepository.getLocalUserId()
                val success = owner != null && syncRunner.retryPendingUploads(owner) == com.diajarkoding.imfit.data.sync.SyncRunResult.SUCCESS
                _state.update { it.copy(uploadRetryFailed = !success) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(uploadRetryFailed = true) } }
            finally { _state.update { it.copy(retryingUploads = false) } }
        }
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
        val avatar = try { authRepository.getSignedAvatarUrl(profile.avatarUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        return profile.copy(avatarUrl = avatar)
    }
    override fun onCleared() { templates.close(); profile.close(); summary.close(); ownerListener.close() }
}
