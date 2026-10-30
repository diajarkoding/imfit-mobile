package com.diajarkoding.imfit.presentation.ui.main

import androidx.lifecycle.ViewModel
import com.diajarkoding.imfit.data.sync.SyncState
import com.diajarkoding.imfit.data.sync.SyncStateProvider
import com.diajarkoding.imfit.domain.model.UserProfileState
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    syncStateProvider: SyncStateProvider
) : ViewModel() {
    val userProfileState: StateFlow<UserProfileState> = authRepository.userProfileState
    val syncState: StateFlow<SyncState> = syncStateProvider.syncState
}
