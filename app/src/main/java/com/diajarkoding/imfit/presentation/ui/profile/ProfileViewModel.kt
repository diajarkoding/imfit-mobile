package com.diajarkoding.imfit.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(private val reads: RemoteReadGateway, private val authRepository: AuthRepository) : ViewModel() {
    private val profile = RemoteResource<RemoteProfile>(reads.auth, viewModelScope)
    val state = profile.state
    init {
        viewModelScope.launch { reads.auth.identity.collect { if (it != null) load() } }
        load()
    }
    fun load(force: Boolean = false) = profile.load("profile", force) {
        val value = reads.profile()
        val avatar = try { authRepository.getSignedAvatarUrl(value.avatarUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        value.copy(avatarUrl = avatar)
    }
    fun refresh() = load(true)
    fun logout(onComplete: () -> Unit) { viewModelScope.launch { authRepository.logout(); onComplete() } }
    override fun onCleared() = profile.close()
}
