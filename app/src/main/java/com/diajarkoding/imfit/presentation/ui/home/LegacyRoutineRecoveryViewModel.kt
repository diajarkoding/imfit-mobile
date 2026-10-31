package com.diajarkoding.imfit.presentation.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.recovery.LegacyRoutineRecovery
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LegacyRoutineRecoveryViewModel @Inject constructor(
    private val recovery: LegacyRoutineRecovery,
    private val auth: AuthRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()
    private var exportOwnerId: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val count: StateFlow<Int> = auth.userProfileState.map { it.userId }.distinctUntilChanged()
        .flatMapLatest { owner -> if (owner == null) flowOf(0) else recovery.observeCount(owner) }
        .catch { error ->
            if (error is CancellationException) throw error
            _message.value = context.getString(R.string.legacy_recovery_read_error)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun beginExport(): Boolean {
        if (_exporting.value) return false
        exportOwnerId = auth.userProfileState.value.userId ?: return false
        _exporting.value = true
        _message.value = null
        return true
    }

    fun exportTo(uri: Uri?) {
        val owner = exportOwnerId
        exportOwnerId = null
        if (uri == null || owner == null) {
            _exporting.value = false
            return
        }
        viewModelScope.launch {
            try {
                check(auth.getLocalUserId() == owner) { "Akun berubah. Ulangi ekspor dari akun pemilik data." }
                val contents = recovery.export(owner)
                check(auth.getLocalUserId() == owner) { "Akun berubah. Ulangi ekspor dari akun pemilik data." }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(contents)
                    } ?: error("File ekspor tidak dapat dibuka.")
                }
                _message.value = context.getString(R.string.legacy_recovery_success)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = context.getString(R.string.legacy_recovery_failure)
            } finally {
                _exporting.value = false
            }
        }
    }
}
