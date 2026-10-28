package com.diajarkoding.imfit.presentation.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.data.remote.online.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class LegacyRecoveryState(
    val inventory: LegacyInventory? = null, val archive: LegacyArchiveReview? = null,
    val loading: Boolean = false, val failed: Boolean = false,
    val fields: Map<String, String?>? = null, val selected: LegacyItem? = null,
)

@HiltViewModel
class LegacyRecoveryViewModel @Inject constructor(
    private val reads: RemoteReadGateway, private val recovery: LegacyRecoveryArchive,
    private val reader: LegacyInventoryReader,
) : ViewModel() {
    private val mutable = MutableStateFlow(LegacyRecoveryState())
    val state = mutable.asStateFlow()
    private var identity: AuthIdentity? = null
    private var job: Job? = null
    private val subscription = reads.auth.onIdentityChanged {
        job?.cancel(); identity = null; mutable.value = LegacyRecoveryState()
    }
    init { viewModelScope.launch { reads.auth.identity.collect { if (it != null) refresh() } }; refresh() }
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val owner = reads.auth.capture().identity
            identity = owner; mutable.value = LegacyRecoveryState(loading = true)
            try {
                val (inventory, archive) = recovery.review()
                if (!reads.auth.isCurrent(owner)) throw StaleAuthException()
                mutable.value = LegacyRecoveryState(inventory, archive)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (reads.auth.isCurrent(owner)) mutable.update { it.copy(loading = false, failed = true) } }
        }
    }
    fun inspect(item: LegacyItem) {
        if (mutable.value.loading) return
        val owner = identity ?: return
        job = viewModelScope.launch {
            mutable.update { it.copy(loading = true, failed = false, selected = item, fields = null) }
            try {
                val fields = reader.record(owner.ownerId, item)
                if (!reads.auth.isCurrent(owner)) throw StaleAuthException()
                mutable.update { it.copy(loading = false, fields = fields) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (reads.auth.isCurrent(owner)) mutable.update { it.copy(loading = false, failed = true) } }
        }
    }
    fun closeDetails() { if (!mutable.value.loading) mutable.update { it.copy(fields = null, selected = null) } }
    fun keepHistory() {
        if (mutable.value.loading) return
        val owner = identity ?: return
        val inventory = mutable.value.inventory as? LegacyInventory.Present ?: return
        job = viewModelScope.launch {
            mutable.update { it.copy(loading = true, failed = false) }
            try {
                val review = recovery.keepHistory(owner, inventory)
                if (!reads.auth.isCurrent(owner)) throw StaleAuthException()
                mutable.update { it.copy(loading = false, archive = review) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (reads.auth.isCurrent(owner)) mutable.update { it.copy(loading = false, failed = true) } }
        }
    }
    override fun onCleared() = subscription.close()
}
