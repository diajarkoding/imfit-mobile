package com.diajarkoding.imfit.presentation.ui.home

import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto
import com.diajarkoding.imfit.data.remote.dto.MuscleCategoryDto
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutTemplateDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.HomeRemoteReadResult
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.UserProfileState
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HomeRemoteReadAndStateTest {

    @Test
    fun workoutTemplateAndLogDtosSortChildrenDeterministically() {
        val legsDto = ExerciseDto(
            id = "ex_squat",
            muscleCategoryId = 6,
            name = "Squat",
            description = "Legs",
            muscleCategory = MuscleCategoryDto(6, "LEGS", "Legs")
        )
        val chestDto = ExerciseDto(
            id = "ex_bench",
            muscleCategoryId = 1,
            name = "Bench Press",
            description = "Chest",
            muscleCategory = MuscleCategoryDto(1, "CHEST", "Chest")
        )

        val templateDto = WorkoutTemplateDto(
            id = "tpl_1",
            userId = "u1",
            name = "Upper Lower",
            templateExercises = listOf(
                TemplateExerciseDto(exerciseId = "ex_squat", orderIndex = 2, sets = 4, reps = 8, exercises = legsDto),
                TemplateExerciseDto(exerciseId = "ex_bench", orderIndex = 0, sets = 3, reps = 10, exercises = chestDto)
            )
        )
        val domainTemplate = templateDto.toDomain()
        assertEquals(listOf("ex_bench", "ex_squat"), domainTemplate.exercises.map { it.exercise.id })
        assertEquals(MuscleCategory.CHEST, domainTemplate.exercises.first().exercise.muscleCategory)

        val logDto = WorkoutLogDto(
            id = "log_1",
            userId = "u1",
            templateName = "Push",
            date = "2026-10-31",
            startTime = "2026-10-31T10:00:00Z",
            endTime = "2026-10-31T10:45:00Z",
            totalVolume = 1200.0,
            exerciseLogs = listOf(
                ExerciseLogDto(
                    id = "el_2",
                    exerciseId = "ex_squat",
                    exerciseName = "Squat",
                    muscleCategory = "LEGS",
                    orderIndex = 1,
                    workoutSets = listOf(
                        WorkoutSetDto(id = "s2", setNumber = 2, weight = 100.0, reps = 8, isCompleted = true),
                        WorkoutSetDto(id = "s1", setNumber = 1, weight = 80.0, reps = 8, isCompleted = true)
                    )
                ),
                ExerciseLogDto(
                    id = "el_1",
                    exerciseId = "ex_bench",
                    exerciseName = "Bench Press",
                    muscleCategory = "CHEST",
                    orderIndex = 0,
                    workoutSets = listOf(
                        WorkoutSetDto(id = "s3", setNumber = 1, weight = 60.0, reps = 10, isCompleted = true)
                    )
                )
            )
        )
        val domainLog = logDto.toDomain()
        assertEquals(listOf("ex_bench", "ex_squat"), domainLog.exerciseLogs.map { it.exercise.id })
        assertEquals(listOf(1, 2), domainLog.exerciseLogs[1].sets.map { it.setNumber })
    }

    @Test
    fun sectionErrorsAreIsolatedAndInitialFailureNeverMasqueradesAsEmpty() = runBlocking {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = ControllableAuthRepository(user)
        val sampleLog = WorkoutLog("log1", "u1", "Push Day", 1000L, 1000L, 61000L, 500f, emptyList())
        val workoutRepo = ControllableWorkoutRepository(
            templatesProvider = { throw IOException("Templates endpoint timeout") },
            lastWorkoutProvider = { HomeRemoteReadResult.RemotePrimary(sampleLog) }
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = HomeViewModel(auth, workoutRepo, testScope)
            awaitCondition { !vm.state.value.isLoading }

            val state = vm.state.value
            assertEquals("Iskan", state.userName)
            assertTrue(state.lastWorkoutState is SectionLoadState.Success)
            assertEquals(sampleLog, state.lastWorkout)
            assertTrue(state.templatesState is SectionLoadState.Error)
            val tplError = state.templatesState as SectionLoadState.Error
            assertFalse(tplError.hasPreviousData)
            assertNull(tplError.previousData)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun successNullFollowedByFailedRefreshPreservesHasPreviousDataTrue() = runBlocking {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = ControllableAuthRepository(user)
        var shouldFailLastWorkout = false
        val workoutRepo = ControllableWorkoutRepository(
            templatesProvider = { HomeRemoteReadResult.RemotePrimary(emptyList()) },
            lastWorkoutProvider = {
                if (shouldFailLastWorkout) throw IOException("Offline during refresh")
                HomeRemoteReadResult.RemotePrimary(null)
            }
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = HomeViewModel(auth, workoutRepo, testScope)
            awaitCondition { vm.state.value.lastWorkoutState is SectionLoadState.Success }

            val initialLastState = vm.state.value.lastWorkoutState as SectionLoadState.Success
            assertNull(initialLastState.data)
            assertTrue(initialLastState.isFromRemote)

            // Now fail on refresh -> must retain hasPreviousData = true and previousData = null
            shouldFailLastWorkout = true
            vm.refresh()
            awaitCondition { vm.state.value.lastWorkoutState is SectionLoadState.Error }

            val errState = vm.state.value.lastWorkoutState as SectionLoadState.Error
            assertTrue("Previously loaded empty (null) state must record hasPreviousData = true", errState.hasPreviousData)
            assertNull(errState.previousData)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun localFallbackResultMarksSectionAsNotFromRemote() = runBlocking {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = ControllableAuthRepository(user)
        val offlineLog = WorkoutLog("offline_log", "u1", "Leg Day", 1000L, 1000L, 121000L, 1000f, emptyList())
        val offlineTemplate = WorkoutTemplate("tpl_local", "u1", "Leg Day", emptyList())

        val workoutRepo = ControllableWorkoutRepository(
            templatesProvider = { HomeRemoteReadResult.LocalFallback(listOf(offlineTemplate), IOException("Offline")) },
            lastWorkoutProvider = { HomeRemoteReadResult.LocalFallback(offlineLog, IOException("Offline")) }
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = HomeViewModel(auth, workoutRepo, testScope)
            awaitCondition { !vm.state.value.isLoading }

            val lastState = vm.state.value.lastWorkoutState as SectionLoadState.Success
            assertFalse(lastState.isFromRemote)
            assertEquals(offlineLog, lastState.data)

            val tplState = vm.state.value.templatesState as SectionLoadState.Success
            assertFalse(tplState.isFromRemote)
            assertEquals(listOf(offlineTemplate), tplState.data)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun staleResponseFromPreviousUserOrGenerationCannotOverwriteNewState() = runBlocking {
        val userA = User("user_A", "User A", "a@imfit.com")
        val userB = User("user_B", "User B", "b@imfit.com")
        val auth = ControllableAuthRepository(userA)
        val slowUserADeferred = CompletableDeferred<HomeRemoteReadResult<List<WorkoutTemplate>>>()
        val templateA = WorkoutTemplate("tpl_A", "user_A", "Routine A", emptyList())
        val templateB = WorkoutTemplate("tpl_B", "user_B", "Routine B", emptyList())

        val workoutRepo = ControllableWorkoutRepository(
            templatesProvider = { userId ->
                if (userId == "user_A") slowUserADeferred.await()
                else HomeRemoteReadResult.RemotePrimary(listOf(templateB))
            },
            lastWorkoutProvider = { HomeRemoteReadResult.RemotePrimary(null) }
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = HomeViewModel(auth, workoutRepo, testScope)
            // Switch to User B while User A's template fetch is still suspended
            auth.switchUser(userB)
            slowUserADeferred.complete(HomeRemoteReadResult.RemotePrimary(listOf(templateA)))

            awaitCondition {
                val s = vm.state.value.templatesState
                s is SectionLoadState.Success && s.data == listOf(templateB)
            }
            assertEquals("User B", vm.state.value.userName)
            assertEquals(listOf(templateB), vm.state.value.templates)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun pullToRefreshUpdatesAllSectionsPreservesContentDuringRefreshAndClearsSpinner() = runBlocking {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = ControllableAuthRepository(user)
        val initialTemplate = WorkoutTemplate("tpl_1", "u1", "Push Day", emptyList())
        val updatedTemplate = WorkoutTemplate("tpl_1", "u1", "Push Day v2", emptyList())
        val updatedLog = WorkoutLog("log_2", "u1", "Push Day v2", 2000L, 2000L, 62000L, 800f, emptyList())

        var manualGate: CompletableDeferred<HomeRemoteReadResult<List<WorkoutTemplate>>>? = null
        val workoutRepo = ControllableWorkoutRepository(
            templatesProvider = {
                val gate = manualGate
                if (gate != null) gate.await()
                else HomeRemoteReadResult.RemotePrimary(listOf(initialTemplate))
            },
            lastWorkoutProvider = {
                if (manualGate != null) HomeRemoteReadResult.RemotePrimary(updatedLog)
                else HomeRemoteReadResult.RemotePrimary(null)
            },
            activeSessionProvider = {
                if (manualGate != null) throw IOException("Active session read error must not fail templates")
                else null
            }
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = HomeViewModel(auth, workoutRepo, testScope)
            awaitCondition { !vm.state.value.isLoading }
            assertEquals(listOf(initialTemplate), vm.state.value.templates)
            assertFalse(vm.state.value.isPullRefreshing)

            // Start manual pull-to-refresh while holding templates response
            val gate = CompletableDeferred<HomeRemoteReadResult<List<WorkoutTemplate>>>()
            manualGate = gate
            auth.updateNameOnRefresh = "Iskan Updated"
            vm.pullToRefresh()

            // During pull-to-refresh: spinner is active, existing templates remain visible (isRefreshing = true), NOT Loading
            assertTrue(vm.state.value.isPullRefreshing)
            val midTplState = vm.state.value.templatesState
            assertTrue(midTplState is SectionLoadState.Success && midTplState.isRefreshing)
            assertEquals(listOf(initialTemplate), vm.state.value.templates)

            // Duplicate manual pullToRefresh while active must be ignored (AC-20)
            val callsBeforeDuplicate = auth.forceRefreshCount
            vm.pullToRefresh()
            assertEquals(callsBeforeDuplicate, auth.forceRefreshCount)

            // Complete manual refresh -> activeSession failure is isolated (P1-1), templates & lastWorkout & profile update, spinner stops
            gate.complete(HomeRemoteReadResult.RemotePrimary(listOf(updatedTemplate)))
            awaitCondition { !vm.state.value.isPullRefreshing }

            assertEquals("Iskan Updated", vm.state.value.userName)
            assertEquals(listOf(updatedTemplate), vm.state.value.templates)
            assertEquals(updatedLog, vm.state.value.lastWorkout)
            assertEquals(1, auth.forceRefreshCount)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun supersededAutoRefreshDoesNotPrematurelyStopManualPullToRefreshSpinner() = runBlocking {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = ControllableAuthRepository(user)
        var callCount = 0
        val autoGate = CompletableDeferred<HomeRemoteReadResult<List<WorkoutTemplate>>>()
        val manualGate = CompletableDeferred<HomeRemoteReadResult<List<WorkoutTemplate>>>()

        val workoutRepo = ControllableWorkoutRepository(
            templatesProvider = {
                callCount++
                when (callCount) {
                    1 -> HomeRemoteReadResult.RemotePrimary(emptyList())
                    2 -> autoGate.await()
                    else -> manualGate.await()
                }
            },
            lastWorkoutProvider = { HomeRemoteReadResult.RemotePrimary(null) }
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = HomeViewModel(auth, workoutRepo, testScope)
            awaitCondition { !vm.state.value.isLoading }

            // Trigger AUTO refresh (callCount = 2, suspended on autoGate)
            vm.refreshHome(HomeRefreshTrigger.AUTO)
            assertFalse(vm.state.value.isPullRefreshing)

            // Supersede with MANUAL pullToRefresh (callCount = 3, cancels auto refresh, sets isPullRefreshing = true)
            vm.pullToRefresh()
            assertTrue("Manual pullToRefresh must keep spinner active even after superseded AUTO refresh", vm.state.value.isPullRefreshing)

            // Only when manualGate completes does isPullRefreshing become false (AC-21)
            val tpl = WorkoutTemplate("t_manual", "u1", "Manual Result", emptyList())
            manualGate.complete(HomeRemoteReadResult.RemotePrimary(listOf(tpl)))
            awaitCondition { !vm.state.value.isPullRefreshing }
            assertEquals(listOf(tpl), vm.state.value.templates)
        } finally {
            testScope.cancel()
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) {
                yield()
            }
        }
    }
}


private class ControllableAuthRepository(initialUser: User?) : AuthRepository {
    private var current: User? = initialUser
    private val _profileState = MutableStateFlow(UserProfileState.fromDirectUser(initialUser))
    override val userProfileState: StateFlow<UserProfileState> = _profileState
    var forceRefreshCount: Int = 0
    var updateNameOnRefresh: String? = null

    fun switchUser(newUser: User?) {
        current = newUser
        _profileState.value = UserProfileState.fromDirectUser(newUser)
    }

    override suspend fun refreshProfile(force: Boolean): Result<User?> {
        if (force) forceRefreshCount++
        updateNameOnRefresh?.let { newName ->
            current = current?.copy(name = newName)
            _profileState.value = UserProfileState.fromDirectUser(current)
        }
        return Result.success(current)
    }
    override suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String?,
        profilePhotoUri: String?
    ): Result<RegisterResult> = Result.failure(UnsupportedOperationException())
    override suspend fun login(email: String, password: String): Result<User> =
        Result.failure(UnsupportedOperationException())
    override suspend fun logout() {
        switchUser(null)
    }
    override suspend fun getCurrentUser(): User? = current
    override suspend fun getLocalUserId(): String? = current?.id
    override suspend fun peekLocalUserId(): String? = current?.id
    override suspend fun isLoggedIn(): Boolean = current != null
    override suspend fun updateProfile(user: User): Result<User> = Result.success(user)
    override suspend fun getSignedAvatarUrl(storagePath: String?): String? = storagePath
}

private class ControllableWorkoutRepository(
    private val templatesProvider: suspend (String) -> HomeRemoteReadResult<List<WorkoutTemplate>>,
    private val lastWorkoutProvider: suspend (String) -> HomeRemoteReadResult<WorkoutLog?>,
    private val activeSessionProvider: suspend () -> WorkoutSession? = { null }
) : WorkoutRepository {
    override suspend fun fetchRemoteTemplates(userId: String): HomeRemoteReadResult<List<WorkoutTemplate>> =
        templatesProvider(userId)

    override suspend fun fetchRemoteLastWorkoutLog(userId: String): HomeRemoteReadResult<WorkoutLog?> =
        lastWorkoutProvider(userId)

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> = emptyList()
    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? = null
    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate =
        WorkoutTemplate("new", userId, name, exercises)
    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? = null
    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? = null
    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? = null
    override suspend fun deleteTemplate(templateId: String): Boolean = true
    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession =
        throw UnsupportedOperationException()
    override suspend fun getActiveSession(): WorkoutSession? = activeSessionProvider()
    override suspend fun updateActiveSession(session: WorkoutSession) = Unit
    override suspend fun finishWorkout(): WorkoutLog? = null
    override suspend fun cancelWorkout() = Unit
    override suspend fun updateSessionRestOverride(seconds: Int) = Unit
    override suspend fun getSessionRestOverride(): Int? = null
    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> = emptyList()
    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? = null
    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? = null
    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? = null
    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> = emptyMap()
}
