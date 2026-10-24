package com.diajarkoding.imfit.presentation.ui.exercise

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter
import androidx.paging.PagingData
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseBrowserRefreshTest {
    private class Repository : ExerciseRepository {
        val records = MutableStateFlow(listOf(Exercise("bench", "Bench", MuscleCategory.CHEST, "")))
        val forces = mutableListOf<Boolean>()
        var result = true
        var barrier: CompletableDeferred<Unit>? = null
        override fun pageExercises(filter: ExerciseCatalogFilter) = flowOf(PagingData.empty<Exercise>())
        override fun observeExercises() = records
        override suspend fun getAllExercises() = records.value
        override suspend fun getExercisesByCategory(category: MuscleCategory) = records.value.filter { it.muscleCategory == category }
        override suspend fun getExerciseById(id: String) = records.value.find { it.id == id }
        override suspend fun searchExercises(query: String) = records.value.filter { it.name.contains(query, true) }
        override suspend fun refreshExercises(force: Boolean): Boolean {
            forces += force
            barrier?.await()
            return result
        }
    }

    @Test fun manualPullBypassesAutomaticCooldownAndRepeatedPullsJoinTheRunningRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = Repository()
        try {
            val vm = ExerciseBrowserViewModel(repository)
            runCurrent()
            repository.forces.clear()
            repository.barrier = CompletableDeferred()
            repeat(10) { vm.refresh(); runCurrent() }
            assertEquals("A burst must not restart/cancel the catalog request", listOf(true), repository.forces)
            assertTrue(vm.state.value.isRefreshing)
            repository.barrier!!.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.isRefreshing)
        } finally { repository.barrier?.complete(Unit); Dispatchers.resetMain() }
    }

    @Test fun failedBooleanRefreshIsShownAsErrorWithoutResettingSearch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = Repository()
            val vm = ExerciseBrowserViewModel(repository)
            runCurrent()
            vm.setQuery("Bench")
            repository.result = false
            vm.refresh(); runCurrent()
            assertTrue("False remote result must not be delivered as a successful refresh", vm.state.value.hasError)
            assertEquals("Bench", vm.state.value.query)
        } finally { Dispatchers.resetMain() }
    }
}
