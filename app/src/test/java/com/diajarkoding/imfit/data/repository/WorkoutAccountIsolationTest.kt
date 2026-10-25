package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.dao.*
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkoutAccountIsolationTest {
    private var owner: String? = "B"
    private val auth = mockk<AuthRepository> { coEvery { getLocalUserId() } answers { owner } }
    private val logs = mockk<WorkoutLogDao>()
    private val templates = mockk<WorkoutTemplateDao>()
    private val exerciseLogs = mockk<ExerciseLogDao>()
    private val sets = mockk<WorkoutSetDao>()
    private val repository = WorkoutLocalStore(auth, exerciseLogs, sets, mockk(), logs, templates,
        mockk(), mockk(), mockk<IMFITDatabase>(), mockk<SyncScheduler>())

    @Test fun anotherAccountsPendingLogCannotBeOpenedByKnownId() = runTest {
        coEvery { logs.getWorkoutLogById("pending-A") } returns WorkoutLogEntity(
            "pending-A", "A", null, "Private", 1, 1, 2, 10f, pendingOperation = "CREATE")
        assertNull(repository.getWorkoutLogById("pending-A"))
        coVerify(exactly = 0) { exerciseLogs.getExerciseLogsByWorkoutLogId(any()) }
    }
    @Test fun anotherAccountsPendingTemplateCannotBeOpenedByKnownId() = runTest {
        coEvery { templates.getTemplateById("pending-A") } returns WorkoutTemplateEntity("pending-A", "A", "Private", pendingOperation = "CREATE")
        assertNull(repository.getTemplateById("pending-A"))
    }
    @Test fun callerCannotSelectForeignOwnerForHistoryTemplatesOrPerformance() = runTest {
        assertTrue(repository.getTemplates("A").isEmpty())
        assertTrue(repository.getWorkoutLogs("A").isEmpty())
        assertNull(repository.getLastWorkoutLog("A"))
        assertTrue(repository.getLastPerformanceForExercise("exercise", "A").isEmpty())
        confirmVerified(logs, templates, sets)
    }
    @Test fun loggedOutCallerCannotOpenRetainedHistory() = runTest {
        owner = null
        assertNull(repository.getWorkoutLogById("pending-A"))
        assertTrue(repository.getWorkoutLogs("A").isEmpty())
        confirmVerified(logs)
    }
    @Test fun foreignTemplateCannotStartSessionUnderCurrentAccount() = runTest {
        try {
            repository.startWorkout(WorkoutTemplate("private-A", "A", "Private", emptyList()))
            fail("Another owner's template cannot create a session")
        } catch (_: IllegalStateException) { }
    }

    @Test fun sameRetainedWorkoutReopensForReturningOwnerButNotForOtherAccount() = runTest {
        coEvery { logs.getWorkoutLogById("pending-A") } returns WorkoutLogEntity(
            "pending-A", "A", null, "Private", 1, 1, 2, 10f, pendingOperation = "CREATE")
        coEvery { exerciseLogs.getExerciseLogsByWorkoutLogId("pending-A") } returns emptyList()
        assertNull(repository.getWorkoutLogById("pending-A"))
        owner = "A"
        assertEquals("pending-A", repository.getWorkoutLogById("pending-A")?.id)
        owner = "B"
        assertNull(repository.getWorkoutLogById("pending-A"))
    }
}
