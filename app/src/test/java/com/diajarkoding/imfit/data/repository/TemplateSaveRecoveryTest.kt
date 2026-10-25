package com.diajarkoding.imfit.data.repository

import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.dao.*
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.remote.*
import com.diajarkoding.imfit.data.sync.RemoteWorkoutCoordinator
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Repository orchestration test; Room transactions/remote transport are fakes, not device proof. */
class TemplateSaveRecoveryTest {
    private val database = mockk<IMFITDatabase>()
    private val templates = mockk<WorkoutTemplateDao>()
    private val children = mockk<TemplateExerciseDao>()
    private val intents = mockk<TemplateWriteIntentDao>()
    private val coordinator = mockk<RemoteWorkoutCoordinator>()
    private val remote = mockk<ImfitAggregateRemoteDataSource>()
    private val auth = mockk<AuthRepository>()
    private val credential = UserSession("fixture-token", "fixture-refresh", expiresIn = 3600,
        tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "owner"))
    private var intent: TemplateWriteIntentEntity? = null
    private var parent: WorkoutTemplateEntity? = null
    private var accountChanged = false
    private lateinit var repository: WorkoutTemplateRepository

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        every { database.workoutTemplateDao() } returns templates
        every { database.templateExerciseDao() } returns children
        every { database.templateWriteIntentDao() } returns intents
        coEvery { templates.getTemplateByIdIncludingDeleted(any()) } answers { parent }
        coEvery { children.getExercisesForTemplateList(any()) } returns emptyList()
        coEvery { intents.get(any(), "owner") } answers { intent }
        coEvery { intents.insert(any()) } answers { intent = firstArg(); Unit }
        coEvery { intents.acknowledge(any(), "owner", any()) } answers {
            if (intent?.payload == thirdArg<String>()) { intent = null; 1 } else 0
        }
        coEvery { templates.insertTemplate(any()) } answers { parent = firstArg(); Unit }
        coEvery { children.deleteExercisesByTemplate(any()) } just Runs
        coEvery { children.insertTemplateExercises(any()) } just Runs
        coEvery { auth.getLocalUserId() } returns "owner"
        coEvery { coordinator.withRemoteSession<WorkoutTemplate>("owner", any()) } coAnswers {
            secondArg<suspend (UserSession) -> WorkoutTemplate>().invoke(credential)
        }
        every { coordinator.requireCurrentSession(credential) } answers { check(!accountChanged) { "Account changed" } }
        repository = WorkoutTemplateRepository(mockk(), coordinator, remote, auth, database)
    }
    @After fun cleanup() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private suspend fun saveFails() {
        try { repository.createTemplate("owner", "Routine", emptyList()); fail("Expected safe failure") }
        catch (failure: com.diajarkoding.imfit.core.network.NetworkOperationException) {
            check(intent != null) { "Failure before request persistence: ${failure.stackTraceToString()}" }
        }
    }
    private fun receipt(request: TemplateWriteRequest) = TemplateWriteReceipt(request.requestId, request.templateId,
        1, request.exercises.size, "2026-10-25T00:00:00Z")

    @Test fun lostResponseRetainsRequestAndRetryUsesExactSameIdentityWithoutBackgroundLoop() = runTest {
        val attempted = mutableListOf<TemplateWriteRequest>()
        coEvery { remote.saveTemplate(any(), "fixture-token") } answers {
            val request = firstArg<TemplateWriteRequest>(); attempted += request
            if (attempted.size == 1) throw IOException("Ambiguous response")
            receipt(request)
        }
        saveFails()
        assertNotNull(intent); assertNull(parent); assertEquals(1, attempted.size)
        val result = repository.createTemplate("owner", "Routine", emptyList())
        assertEquals(attempted[0], attempted[1])
        assertEquals(attempted[0].templateId, result.id)
        assertNull(intent); assertEquals("SYNCED", parent?.syncStatus)
    }
    @Test fun wrongReceiptCannotClearDurableRecoveryRequest() = runTest {
        coEvery { remote.saveTemplate(any(), any()) } answers { receipt(firstArg()).copy(requestId = "wrong") }
        saveFails()
        assertNotNull(intent); assertNull(parent)
        coVerify(exactly = 0) { intents.acknowledge(any(), any(), any()) }
    }
    @Test fun accountChangeDuringRequestCannotCacheOrAcknowledgeOldAccountData() = runTest {
        coEvery { remote.saveTemplate(any(), any()) } answers { accountChanged = true; receipt(firstArg()) }
        saveFails()
        assertNotNull(intent); assertNull(parent)
        coVerify(exactly = 0) { templates.insertTemplate(any()) }
        coVerify(exactly = 0) { intents.acknowledge(any(), any(), any()) }
    }
    @Test fun newerLocalEditWhileRequestRunsIsPreservedInsteadOfAcknowledged() = runTest {
        coEvery { remote.saveTemplate(any(), any()) } answers {
            val request = firstArg<TemplateWriteRequest>()
            parent = WorkoutTemplateEntity(request.templateId, "owner", "New draft", pendingOperation = "UPDATE")
            receipt(request)
        }
        saveFails()
        assertNotNull(intent); assertEquals("New draft", parent?.name)
        coVerify(exactly = 0) { intents.acknowledge(any(), any(), any()) }
    }

    @Test fun missingCatalogChildCannotBeSilentlyConvertedIntoUserRemoval() = runTest {
        parent = WorkoutTemplateEntity("template", "owner", "Routine", syncStatus = "SYNCED", remoteRevision = 1)
        coEvery { children.getExercisesForTemplateList("template") } returns listOf(
            TemplateExerciseEntity("template", "missing", 0, 3, 10, 60, syncStatus = "SYNCED"))
        val catalog = mockk<ExerciseDao>()
        every { database.exerciseDao() } returns catalog
        coEvery { catalog.getExerciseById("missing") } returns null
        try { repository.updateTemplate("template", "Routine", emptyList()); fail("Incomplete cache must not imply removal") }
        catch (failure: com.diajarkoding.imfit.core.network.NetworkOperationException) {
            assertTrue(failure.cause?.message.orEmpty().contains("Incomplete catalog"))
        }
        coVerify(exactly = 0) { remote.saveTemplate(any(), any()) }
        assertEquals("Routine", parent?.name); assertNull(intent)
    }
}
