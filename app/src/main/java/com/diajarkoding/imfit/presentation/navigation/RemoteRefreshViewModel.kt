package com.diajarkoding.imfit.presentation.navigation

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncRunResult
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Non-blocking refresh on route/foreground entry, coalesced per authenticated user. */
@HiltViewModel
class RemoteRefreshViewModel @Inject constructor(
    private val auth: AuthRepository, private val sync: SyncRunner
) : ViewModel() {
    private val mutex = Mutex()
    private var lastUser: String? = null
    private var lastAttempt: Long? = null
    fun refresh() {
        viewModelScope.launch {
            mutex.withLock {
                val userId = auth.getLocalUserId() ?: return@withLock
                val now = SystemClock.elapsedRealtime()
                if (lastUser == userId && lastAttempt?.let { now - it < 30_000 } == true) return@withLock
                lastUser = userId
                lastAttempt = now
                try {
                    if (sync.run(userId) == SyncRunResult.NO_AUTHENTICATED_USER) lastUser = null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Durable retry remains owned by SyncWorker, local UI stays usable. */ }
            }
        }
    }
}
