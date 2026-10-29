package com.diajarkoding.imfit.presentation.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.data.remote.online.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class RemoteHistoryViewModel @Inject constructor(
    private val reads: RemoteReadGateway,
    private val changes: OnlineMutationChanges,
) : ViewModel() {
    private val feed = reads.historyFeed(viewModelScope)
    val page = feed.state
    private val aggregate = RemoteResource<ProgressSummary>(reads.auth, viewModelScope)
    val totals = aggregate.state
    private val detail = RemoteResource<WorkoutDetail>(reads.auth, viewModelScope)
    val selected = detail.state
    private var date: LocalDate? = null
    private var window: DateWindow? = null
    init {
        viewModelScope.launch { reads.auth.identity.collect { if (it != null) date?.let { day -> loadDay(day, false) } } }
        viewModelScope.launch { changes.events.collect { change ->
            reads.auth.synchronizedState {
                if (change !is WorkoutCommitted || !reads.auth.isCurrent(change.identity)) return@synchronizedState
                val query = window?.takeIf { it.containsWorkout(change.startedAt) } ?: return@synchronizedState
                feed.refresh()
                aggregate.load(query.toString(), true) { reads.read(OnlineRpc.progress(query)) }
            }
        } }
        runCatching { reads.auth.capture() }
    }
    fun initialize(day: LocalDate, force: Boolean = false) {
        if (date == day && !force) return
        date = day
        loadDay(day, force)
    }
    private fun loadDay(day: LocalDate, force: Boolean) {
        if (reads.auth.identity.value == null) return
        val query = DateWindow(day.toString(), day.plusDays(1).toString(), ZoneId.systemDefault().id)
        window = query
        feed.select(QueryKey.History(query))
        if (force) feed.refresh()
        aggregate.load(query.toString(), force) { reads.read(OnlineRpc.progress(query)) }
    }
    fun refresh() { date?.let { initialize(it, true) } }
    fun retry() = feed.retry()
    fun access(id: String) { page.value.items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(feed::access) }
    fun more() { page.value.items.lastOrNull()?.let { access(it.id) } }
    fun open(id: String, force: Boolean = false) = detail.load(id, force) { reads.read(OnlineRpc.workoutDetail(id)) }
    override fun onCleared() { feed.close(); aggregate.close(); detail.close() }
}
