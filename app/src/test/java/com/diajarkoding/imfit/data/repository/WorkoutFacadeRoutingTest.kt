package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkoutFacadeRoutingTest {
    private val templates = mockk<WorkoutTemplateRepository>()
    private val logs = mockk<WorkoutLogRepository>()
    private val sessions = mockk<WorkoutSessionManager>()
    private val facade = WorkoutRepositoryImpl(templates, logs, sessions)
    private val template = WorkoutTemplate("template", "owner", "Routine", emptyList())

    @Test fun templateWritesUseRemoteSpecialistNotRoomFirstImplementation() = runTest {
        coEvery { templates.createTemplate("owner", "Routine", emptyList()) } returns template
        coEvery { templates.updateTemplateExercises("template", emptyList()) } returns template
        assertEquals(template, facade.createTemplate("owner", "Routine", emptyList()))
        assertEquals(template, facade.updateTemplateExercises("template", emptyList()))
        confirmVerified(logs, sessions)
    }
    @Test fun historyReadUsesLogSpecialist() = runTest {
        coEvery { logs.getWorkoutLogs("owner") } returns emptyList()
        assertTrue(facade.getWorkoutLogs("owner").isEmpty())
        coVerify(exactly = 1) { logs.getWorkoutLogs("owner") }
        confirmVerified(templates, sessions)
    }
    @Test fun activeSessionEditsAndCompletionKeepSingleSessionOwner() = runTest {
        val session = WorkoutSession("session", "template", "Routine", 1, emptyList())
        coEvery { sessions.startWorkout(template) } returns session
        coEvery { sessions.finishWorkout() } returns null
        assertEquals(session, facade.startWorkout(template))
        assertNull(facade.finishWorkout())
        coVerifySequence { sessions.startWorkout(template); sessions.finishWorkout() }
        confirmVerified(templates, logs)
    }
    @Test fun completionAttemptsUploadOnlyAfterDurableLocalFinish() = runTest {
        val local = mockk<WorkoutLocalStore>()
        val completed = WorkoutLog("session", "owner", "Routine", 1, 1, 2, 0f, emptyList())
        coEvery { local.finishWorkout() } returns completed
        coEvery { logs.attemptFinishedWorkout("owner", "session") } just Runs
        val manager = WorkoutSessionManager(local, logs)
        assertEquals(completed, manager.finishWorkout())
        coVerifySequence { local.finishWorkout(); logs.attemptFinishedWorkout("owner", "session") }
    }
    @Test fun absentOrUnfinishedSessionDoesNotUploadAnything() = runTest {
        val local = mockk<WorkoutLocalStore> { coEvery { finishWorkout() } returns null }
        assertNull(WorkoutSessionManager(local, logs).finishWorkout())
        confirmVerified(logs)
    }
}
