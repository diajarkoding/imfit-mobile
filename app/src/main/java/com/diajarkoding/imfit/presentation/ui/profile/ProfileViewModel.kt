package com.diajarkoding.imfit.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.avatarPresentationUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val reads: RemoteReadGateway,
    private val authRepository: AuthRepository,
    private val changes: OnlineMutationChanges,
    sessions: OnlineSessionStore,
) : ViewModel() {
    private val profile = RemoteResource<RemoteProfile>(reads.auth, viewModelScope)
    val state = profile.state
    val sessionState = sessions.state
    init {
        viewModelScope.launch { reads.auth.identity.collect { if (it != null) load() } }
        viewModelScope.launch { changes.events.collect { change ->
            reads.auth.synchronizedState {
                if (change is ProfileChanged && reads.auth.isCurrent(change.identity)) load(true)
            }
        } }
        load()
    }
    fun load(force: Boolean = false) = profile.load("profile", force) {
        val value = reads.profile()
        value.copy(avatarUrl = authRepository.avatarPresentationUrl(value.avatarUrl))
    }
    fun refresh() = load(true)
    fun logout(onComplete: () -> Unit) { viewModelScope.launch { authRepository.logout(); onComplete() } }
    override fun onCleared() = profile.close()
}
