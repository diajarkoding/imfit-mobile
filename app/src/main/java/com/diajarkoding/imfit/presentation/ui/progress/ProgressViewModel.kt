package com.diajarkoding.imfit.presentation.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.flow.combine
import java.time.YearMonth

data class ProgressState(
    val userName: String = "",
    val userEmail: String = "",
    val userBirthDate: String? = null,
    val userProfilePhotoUri: String? = null,
    val workoutDates: Set<LocalDate> = emptySet(),
    val workoutLogsByDate: Map<LocalDate, List<WorkoutLog>> = emptyMap(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val aggregate: ProgressSummary? = null,
    val dayAggregate: ProgressSummary? = null,
    val markerCounts: Map<LocalDate, Long> = emptyMap(),
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val remoteMode: Boolean = false,
    val dayLoading: Boolean = false,
    val dayError: Boolean = false,
    val dayStale: Boolean = false,
    val markersLoaded: Boolean = false
)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val reads: RemoteReadGateway,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressState(remoteMode = true))
    val state: StateFlow<ProgressState> = _state.asStateFlow()
    private val profile = RemoteResource<RemoteProfile>(reads.auth, viewModelScope)
    private val aggregate = RemoteResource<ProgressSummary>(reads.auth, viewModelScope)
    private val markers = RemoteResource<DateMarkers>(reads.auth, viewModelScope)
    private val day = RemoteResource<ProgressSummary>(reads.auth, viewModelScope)
    private var window: DateWindow? = null
    private var selectedDay: LocalDate? = null
    private val ownerListener = reads.auth.onIdentityChanged { _state.value = ProgressState(isLoading = false, remoteMode = true) }

    init {
        viewModelScope.launch {
            combine(profile.state, aggregate.state, markers.state, day.state) { p, a, m, d ->
                val identity = reads.auth.identity.value
                val user = p.data.takeIf { p.identity == identity }
                val summary = a.data.takeIf { a.identity == identity }
                val dates = m.data.takeIf { m.identity == identity }
                identity to ProgressState(userName = user?.name.orEmpty(), userEmail = user?.email.orEmpty(),
                    userBirthDate = user?.birthDate, userProfilePhotoUri = user?.avatarUrl,
                    workoutDates = dates?.dates.orEmpty().map { LocalDate.parse(it.date) }.toSet(),
                    isLoading = window == null || (a.loading || m.loading) && (summary == null || dates == null),
                    hasError = a.error != null || m.error != null, aggregate = summary,
                    markerCounts = dates?.dates.orEmpty().associate { LocalDate.parse(it.date) to it.workoutCount },
                    dayAggregate = d.data.takeIf { d.identity == identity }, refreshing = a.loading || m.loading,
                    stale = a.stale || m.stale, remoteMode = true,
                    dayLoading = d.loading, dayError = d.error != null, dayStale = d.stale,
                    markersLoaded = dates != null)
            }.collect { (identity, value) -> reads.auth.synchronizedState {
                if (reads.auth.identity.value == identity) _state.value = value
            } }
        }
        viewModelScope.launch { reads.auth.identity.collectLatest { if (it != null) loadData(force = false) } }
        runCatching { reads.auth.capture() }
    }

    fun loadData() = loadData(force = true)

    private fun loadData(force: Boolean) {
        val query = window ?: return
        profile.load("profile", force) { reads.profile() }
        aggregate.load(query.toString(), force) { reads.read(OnlineRpc.progress(query)) }
        markers.load(query.toString(), force) { reads.read(OnlineRpc.dateMarkers(query)) }
        selectedDay?.let { selectDay(it, force) }
    }

    fun selectMonth(month: YearMonth) = setWindow(month.atDay(1), month.plusMonths(1).atDay(1))
    fun selectYear(year: Int) = setWindow(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))
    private fun setWindow(start: LocalDate, end: LocalDate) {
        val next = DateWindow(start.toString(), end.toString(), ZoneId.systemDefault().id)
        if (next == window) return
        window = next
        profile.load("profile") { reads.profile() }
        aggregate.load(next.toString()) { reads.read(OnlineRpc.progress(next)) }
        markers.load(next.toString()) { reads.read(OnlineRpc.dateMarkers(next)) }
    }
    fun selectDay(date: LocalDate, force: Boolean = false) {
        selectedDay = date
        val query = DateWindow(date.toString(), date.plusDays(1).toString(), ZoneId.systemDefault().id)
        day.load(query.toString(), force) { reads.read(OnlineRpc.progress(query)) }
    }
    override fun onCleared() { profile.close(); aggregate.close(); markers.close(); day.close(); ownerListener.close() }

    fun logout(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}
