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
    val identity: AuthIdentity? = null,
    val inventory: LegacyInventory? = null,
    val archive: LegacyArchiveReview? = null,
    val review: LegacyRecoveryReview? = null,
    val covered: Set<LegacyItem> = emptySet(),
    val historical: Set<LegacyItem> = emptySet(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val fields: Map<String, String?>? = null,
    val selected: LegacyItem? = null,
    val legacySessions: List<LegacyRecoveryItem> = emptyList(),
    val legacyCursor: String? = null,
    val hasMoreLegacy: Boolean = false,
    val legacyPageLoaded: Boolean = false,
    val result: LegacyRecoveryResult? = null,
)

@HiltViewModel
class LegacyRecoveryViewModel @Inject constructor(
    private val reads: RemoteReadGateway,
    private val recovery: LegacyRecoveryArchive,
    private val aggregates: LegacyAggregateReader,
    private val archiveStore: LegacyArchiveStore,
    private val coordinator: LegacyRecoveryCoordinator,
    private val sessions: OnlineSessionStore,
) : ViewModel() {
    private val mutable = MutableStateFlow(LegacyRecoveryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val subscription = reads.auth.onIdentityChanged {
        job?.cancel()
        mutable.value = LegacyRecoveryState(identity = it)
    }
    init {
        viewModelScope.launch {
            reads.auth.identity.collect { if (it != null) refresh() }
        }
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val owner = try { reads.auth.capture().identity }
            catch (_: Exception) { return@launch }
            mutable.value = LegacyRecoveryState(identity = owner, loading = true)
            try { loadReview(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(owner) }
        }
    }

    private suspend fun loadReview(owner: AuthIdentity) {
        val review = coordinator.review()
        require(review.identity == owner)
        val archive = archiveStore.load(owner.ownerId)
        val covered = coordinator.validatedCoverage(owner, review.inventory)
        val historical = (review.inventory as? LegacyInventory.Present)?.let { archive?.coveredItems(it) }.orEmpty()
        reads.auth.withCurrent(owner) {
            mutable.update { it.copy(identity = owner, inventory = review.inventory, archive = archive,
                review = review, covered = covered, historical = historical, loading = false, failed = review.failures.isNotEmpty()) }
        }
    }

    fun confirm(identity: AuthIdentity?, action: () -> Unit) {
        if (identity == null || mutable.value.identity != identity || !reads.auth.isCurrent(identity)) return
        reads.auth.withCurrent(identity, action)
    }

    private fun launchAction(block: suspend (AuthIdentity) -> Unit) {
        if (mutable.value.loading) return
        val owner = mutable.value.identity ?: return
        if (!reads.auth.isCurrent(owner)) return
        job = viewModelScope.launch {
            mutable.update { it.copy(loading = true, failed = false) }
            try { block(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(owner) }
        }
    }

    private fun fail(owner: AuthIdentity) {
        if (reads.auth.isCurrent(owner)) mutable.update { it.copy(loading = false, failed = true) }
    }

    fun inspect(item: LegacyItem) = launchAction { owner ->
        val displayed = mutable.value.inventory as? LegacyInventory.Present
            ?: throw IllegalStateException("Legacy inventory unavailable")
        val aggregate = aggregates.read(owner.ownerId, item, displayed.evidence())
        val fields = buildMap<String, String?> {
            for ((kind, rows) in aggregate.records) rows.forEachIndexed { index, row ->
                put("${kind.name}:$index", row.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key: ${value ?: "null"}" })
            }
        }
        reads.auth.withCurrent(owner) {
            mutable.update { it.copy(loading = false, fields = fields, selected = item) }
        }
    }

    fun closeDetails() {
        if (!mutable.value.loading) mutable.update { it.copy(fields = null, selected = null) }
    }

    fun keepHistory() = launchAction { owner ->
        val inventory = mutable.value.inventory as? LegacyInventory.Present
            ?: throw IllegalStateException("Legacy inventory unavailable")
        recovery.keepHistory(owner, inventory)
        loadReview(owner)
    }

    fun importWorkout(item: LegacyItem) = prepare { inventory ->
        LegacySelection.ImportWorkout(inventory, item)
    }

    fun replayTemplateIntent(item: LegacyItem) = prepare { inventory ->
        LegacySelection.ReplayTemplateIntent(inventory, item)
    }

    private fun prepare(selection: (LegacyInventory.Present) -> LegacySelection) = launchAction { owner ->
        val inventory = mutable.value.inventory as? LegacyInventory.Present
            ?: throw IllegalStateException("Legacy inventory unavailable")
        val action = coordinator.prepare(owner, selection(inventory))
        val result = coordinator.submit(owner, action.actionId)
        reads.auth.withCurrent(owner) { mutable.update { it.copy(result = result, selected = null, fields = null) } }
        loadReview(owner)
    }

    fun resolveAction(actionId: String) = launchAction { owner ->
        val result = coordinator.resolve(owner, actionId)
        reads.auth.withCurrent(owner) { mutable.update { it.copy(result = result) } }
        loadReview(owner)
    }

    fun retryOriginal(actionId: String) = launchAction { owner ->
        val result = coordinator.submit(owner, actionId)
        reads.auth.withCurrent(owner) { mutable.update { it.copy(result = result) } }
        loadReview(owner)
    }

    fun abandonActive(item: LegacyItem) = launchAction { owner ->
        val inventory = mutable.value.inventory as? LegacyInventory.Present
            ?: throw IllegalStateException("Legacy inventory unavailable")
        coordinator.abandonLocalActive(owner, inventory, item)
        reads.auth.withCurrent(owner) { mutable.update { it.copy(selected = null, fields = null) } }
        loadReview(owner)
    }

    fun loadLegacyPage() = launchAction { owner ->
        val before = mutable.value
        if (before.legacyPageLoaded && !before.hasMoreLegacy) {
            mutable.update { it.copy(loading = false) }
            return@launchAction
        }
        val page = coordinator.legacyPage(owner, before.legacyCursor)
        require(!page.pageInfo.hasMore || page.pageInfo.nextCursor != before.legacyCursor)
        reads.auth.withCurrent(owner) {
            mutable.update { it.copy(loading = false, legacyPageLoaded = true,
                legacySessions = (before.legacySessions + page.data).distinctBy(LegacyRecoveryItem::sessionId),
                legacyCursor = page.pageInfo.nextCursor, hasMoreLegacy = page.pageInfo.hasMore) }
        }
    }

    fun archiveServerSession(item: LegacyRecoveryItem) = launchAction { owner ->
        val action = coordinator.prepare(owner, LegacySelection.ArchiveServerSession(item))
        val result = coordinator.submit(owner, action.actionId)
        reads.auth.withCurrent(owner) {
            mutable.update { it.copy(result = result, legacySessions = emptyList(), legacyCursor = null,
                hasMoreLegacy = false, legacyPageLoaded = false) }
        }
        loadReview(owner)
    }

    fun resumeRemote(onResume: (String, String) -> Unit) = launchAction { owner ->
        val target = mutable.value.review?.remoteCurrent?.session
            ?: throw IllegalStateException("Session unavailable")
        val latest = sessions.reload(target.id) ?: throw OnlineFailure(RemoteError.NotFound)
        require(!latest.reviewRequired && latest.effectiveStatus in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED))
        val confirmed = if (latest.effectiveStatus == SessionStatus.PAUSED)
            sessions.command(latest.id, SessionCommand.Resume) else latest
        reads.auth.withCurrent(owner) {
            mutable.update { it.copy(loading = false) }
            onResume(confirmed.id, confirmed.templateId.orEmpty())
        }
    }

    fun cancelRemote() = launchAction { owner ->
        val target = mutable.value.review?.remoteCurrent?.session
            ?: throw IllegalStateException("Session unavailable")
        sessions.reload(target.id)
        sessions.cancel(target.id)
        loadReview(owner)
    }

    fun acknowledgeExpiry() = launchAction { owner ->
        val target = mutable.value.review?.remoteCurrent?.session
            ?: throw IllegalStateException("Session unavailable")
        sessions.acknowledgeExpiry(target.id)
        loadReview(owner)
    }

    override fun onCleared() = subscription.close()
}
