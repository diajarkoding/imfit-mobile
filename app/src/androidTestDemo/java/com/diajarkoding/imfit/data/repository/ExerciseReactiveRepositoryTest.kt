package com.diajarkoding.imfit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.local.entity.UserEntity
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncRunResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ExerciseReactiveRepositoryTest {
    private lateinit var db: IMFITDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), IMFITDatabase::class.java).build()
    }
    @After fun close() { db.close() }
    @Test fun roomUpdatesFlowToExistingCatalogAndEmptySearchNeverFallsBackToFakeRecords() = runBlocking {
        val runner = object : SyncRunner { override suspend fun run(expectedUserId: String?) = SyncRunResult.SUCCESS }
        val repo = ExerciseRepositoryImpl(db.exerciseDao(), runner)
        assertTrue(repo.getAllExercises().isEmpty())
        db.exerciseDao().insertExercise(ExerciseEntity("exercise", "Before", 1, ""))
        assertEquals("Before", repo.observeExercises().first().single().name)
        val changed = async(start = CoroutineStart.UNDISPATCHED) { repo.observeExercises().first { it.firstOrNull()?.name == "After" } }
        db.exerciseDao().insertExercise(ExerciseEntity("exercise", "After", 1, ""))
        assertEquals("After", withTimeout(5000) { changed.await() }.single().name)
        assertTrue(repo.searchExercises("does-not-exist").isEmpty())
        db.exerciseDao().deactivateExercise("exercise")
        assertTrue(repo.observeExercises().first().isEmpty())
        assertNotNull(repo.getExerciseById("exercise")) // Historical references remain resolvable.
    }
    @Test fun catalogRefreshIsIndependentFromFailedWorkoutPushAndCoalescesConcurrentEntries() = runBlocking {
        var masterCalls = 0
        val runner = object : SyncRunner {
            override suspend fun run(expectedUserId: String?) = error("Unrelated upload failure")
            override suspend fun refreshMasterData(): SyncRunResult { masterCalls++; delay(20); return SyncRunResult.SUCCESS }
        }
        val repo = ExerciseRepositoryImpl(db.exerciseDao(), runner)
        coroutineScope { repeat(5) { launch { repo.refreshExercises() } } }
        assertEquals(1, masterCalls)
        assertTrue(repo.refreshExercises(force = true))
        assertEquals(2, masterCalls)
    }
    @Test fun failedRefreshKeepsOfflineCatalogAndCancellationPropagates() = runBlocking {
        db.exerciseDao().insertExercise(ExerciseEntity("exercise", "Offline", 1, ""))
        var cancel = false
        val runner = object : SyncRunner { override suspend fun run(expectedUserId: String?): SyncRunResult {
            if (cancel) throw CancellationException("test")
            return SyncRunResult.RETRYABLE_FAILURE
        } }
        val repo = ExerciseRepositoryImpl(db.exerciseDao(), runner)
        assertFalse(repo.refreshExercises())
        assertEquals("Offline", repo.getAllExercises().single().name)
        cancel = true
        try { repo.refreshExercises(); fail() } catch (_: CancellationException) { }
    }
    @Test fun profileCacheIsPersistedObservableAndScopedToTheOwner() = runBlocking {
        val dao = db.userDao()
        dao.insertUser(UserEntity("user", "Before", "synthetic@example.invalid"))
        val observed = async(start = CoroutineStart.UNDISPATCHED) { dao.observeUser("user").first { it?.name == "After" } }
        dao.insertUser(UserEntity("other", "Other", "other@example.invalid"))
        dao.insertUser(UserEntity("user", "After", "synthetic@example.invalid"))
        assertEquals("After", withTimeout(5000) { observed.await() }?.name)
        assertEquals("Other", dao.getUserById("other")?.name)
    }
}
