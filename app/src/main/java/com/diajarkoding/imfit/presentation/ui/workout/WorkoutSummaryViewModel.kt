package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import com.diajarkoding.imfit.data.remote.online.*

data class WorkoutSummaryState(
    val workoutLog: WorkoutLog? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class WorkoutSummaryViewModel @Inject constructor(
    private val reads: RemoteReadGateway
) : ViewModel() {

    private val resource = RemoteResource<WorkoutDetail>(reads.auth, viewModelScope)
    val state = resource.state

    fun loadWorkoutLog(workoutLogId: String) {
        resource.load(workoutLogId) { reads.read(OnlineRpc.workoutDetail(workoutLogId)) }
    }
    fun refresh() { state.value.key?.let { id -> resource.load(id, true) { reads.read(OnlineRpc.workoutDetail(id)) } } }
    override fun onCleared() = resource.close()
}
