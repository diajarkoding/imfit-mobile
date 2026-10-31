package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutineFormMode {
    SETUP,
    EDIT
}

@Serializable
data class RoutineFlowContext(
    val flowId: String,
    val routineId: String,
    val ownerId: String,
    val mode: RoutineFormMode
)

data class RoutineSelectionResult(
    val context: RoutineFlowContext,
    val resultId: String,
    val selectedExercises: List<Exercise>
)

data class RoutineFlowState(
    val context: RoutineFlowContext,
    val selectedExercises: List<Exercise> = emptyList(),
    val selectionRevision: Long = 0L,
    val pendingResultId: String? = null,
    val acceptedResultId: String? = null
) {
    val selectedExerciseIds: List<String> get() = selectedExercises.map { it.id }

    val pendingResult: RoutineSelectionResult?
        get() = pendingResultId?.takeUnless { it == acceptedResultId }?.let {
            RoutineSelectionResult(context, it, selectedExercises)
        }
}
