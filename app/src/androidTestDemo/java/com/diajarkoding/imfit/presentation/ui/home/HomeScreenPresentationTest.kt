package com.diajarkoding.imfit.presentation.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.recovery.LegacyRoutineRecovery
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.HomeRemoteReadResult
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.UserProfileState
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.presentation.components.common.ImfitMainAppBar
import com.diajarkoding.imfit.theme.IMFITTheme
import com.diajarkoding.imfit.theme.LocaleManager
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutTestEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class HomeScreenPresentationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = LocaleManager.getUpdatedContext(rule.activity).getString(id)

    @Test
    fun loadingShowsComponentMatchingShimmersThenEmptyWorkoutRendersZeroMetrics() {
        val lastLogGate = CompletableDeferred<HomeRemoteReadResult<WorkoutLog?>>()
        val templatesGate = CompletableDeferred<Result<List<WorkoutTemplate>>>()
        val auth = FakeHomeUiAuthRepository(
            UserProfileState(isProfileLoadingOverride = true)
        )
        val workoutRepo = FakeHomeUiWorkoutRepository(
            onFetchLastWorkout = { lastLogGate.await() }
        )
        val routineRepo = FakeHomeUiRoutineRepository(
            onGetRoutines = { templatesGate.await() }
        )
        val recoveryViewModel = LegacyRoutineRecoveryViewModel(
            FakeHomeUiLegacyRoutineRecovery(), auth, rule.activity
        )
        val viewModel = HomeViewModel(auth, workoutRepo, routineRepo)
        rule.setContent {
            IMFITTheme {
                Column {
                    val profile by auth.userProfileState.collectAsStateWithLifecycle()
                    ImfitMainAppBar(
                        title = "IMFIT",
                        profilePhotoUri = profile.resolvedAvatarUrl,
                        isProfileLoading = profile.isAvatarLoading,
                        onProfileClick = {}
                    )
                    HomeScreen(
                        onNavigateToWorkoutDetail = {},
                        viewModel = viewModel,
                        recoveryViewModel = recoveryViewModel
                    )
                }
            }
        }

        // 1. Verify shimmer placeholders match all async components while loading
        rule.onNodeWithTag("app_bar_avatar_shimmer", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("shimmer_welcome_section").assertIsDisplayed()
        rule.onNodeWithTag("shimmer_last_workout_card").assertIsDisplayed()
        rule.onAllNodesWithTag("shimmer_workout_card").assertCountEquals(3)

        // 2. Complete profile & empty last workout + empty templates
        val user = User("u1", "Iskan", "iskan@imfit.com")
        auth.emitProfile(UserProfileState.fromDirectUser(user))
        lastLogGate.complete(HomeRemoteReadResult.RemotePrimary(null))
        templatesGate.complete(Result.success(emptyList()))

        rule.waitForIdle()
        rule.onNodeWithTag("home_last_workout_card").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.workout_duration_zero)).assertIsDisplayed()
        rule.onAllNodesWithTag("legacy_routine_recovery").assertCountEquals(0)
    }

    @Test
    fun failedLastWorkoutAndTemplatesShowSectionErrorsInsteadOfZeroOrEmpty() {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = FakeHomeUiAuthRepository(UserProfileState.fromDirectUser(user))
        var retryCount = 0
        val workoutRepo = FakeHomeUiWorkoutRepository(
            onFetchLastWorkout = {
                throw IOException("Network failure")
            }
        )
        val routineRepo = FakeHomeUiRoutineRepository(
            onGetRoutines = {
                retryCount++
                Result.failure(IOException("Network failure"))
            }
        )
        val viewModel = HomeViewModel(auth, workoutRepo, routineRepo)
        val recoveryViewModel = LegacyRoutineRecoveryViewModel(
            FakeHomeUiLegacyRoutineRecovery(), auth, rule.activity
        )

        rule.setContent {
            IMFITTheme {
                HomeScreen(
                    onNavigateToWorkoutDetail = {},
                    viewModel = viewModel,
                    recoveryViewModel = recoveryViewModel
                )
            }
        }

        rule.waitForIdle()
        rule.onNodeWithTag("home_last_workout_error").assertIsDisplayed()
        rule.onAllNodesWithTag("home_last_workout_card").assertCountEquals(0)
        rule.onNodeWithTag("home_templates_error").assertIsDisplayed()

        val beforeRetry = retryCount
        rule.onNodeWithTag("home_templates_error_retry").performClick()
        rule.waitForIdle()
        assertEquals(beforeRetry + 1, retryCount)
    }

    @Test
    fun legacyRecoveryShowsReadOnlyNoticeAndEnabledExportActionForCurrentOwner() {
        val auth = FakeHomeUiAuthRepository(
            UserProfileState.fromDirectUser(User("u1", "Iskan", "iskan@imfit.com"))
        )
        val recovery = FakeHomeUiLegacyRoutineRecovery(mapOf("u1" to 2, "other" to 5))
        val recoveryViewModel = LegacyRoutineRecoveryViewModel(recovery, auth, rule.activity)
        val viewModel = HomeViewModel(
            auth,
            FakeHomeUiWorkoutRepository { HomeRemoteReadResult.RemotePrimary(null) },
            FakeHomeUiRoutineRepository { Result.success(emptyList()) }
        )
        rule.setContent {
            WorkoutTestEnvironment(indonesian = true) {
                HomeScreen(
                    onNavigateToWorkoutDetail = {},
                    viewModel = viewModel,
                    recoveryViewModel = recoveryViewModel
                )
            }
            }

        rule.waitUntil(10_000) { rule.onAllNodesWithTag("legacy_routine_recovery").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("legacy_routine_recovery").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(LocaleManager.getUpdatedContext(rule.activity).getString(R.string.legacy_recovery_title, 2))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.legacy_recovery_notice)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.legacy_recovery_privacy)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.legacy_recovery_export))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        assertEquals(0, recovery.exportCalls)
    }

    @Test
    fun homeLastWorkoutCardRendersThreeColumnLegacyMultiModalSummary() {
        val bench = Exercise(
            id = "ex_bench",
            name = "Bench Press",
            muscleCategory = MuscleCategory.CHEST,
            description = "Chest",
            defaultTrackingType = TrackingType.WEIGHT_REPS,
            modality = ExerciseModality.STRENGTH
        )
        val dips = Exercise(
            id = "ex_dips",
            name = "Weighted Dips",
            muscleCategory = MuscleCategory.CHEST,
            description = "Chest",
            defaultTrackingType = TrackingType.BODYWEIGHT_REPS,
            modality = ExerciseModality.CALISTHENICS
        )
        val pushups = Exercise(
            id = "ex_pushups",
            name = "Push-ups",
            muscleCategory = MuscleCategory.CHEST,
            description = "Chest",
            defaultTrackingType = TrackingType.REPS_ONLY,
            modality = ExerciseModality.CALISTHENICS
        )
        val plank = Exercise(
            id = "ex_plank",
            name = "Plank",
            muscleCategory = MuscleCategory.CORE,
            description = "Core",
            defaultTrackingType = TrackingType.DURATION,
            modality = ExerciseModality.ISOMETRIC
        )
        val run = Exercise(
            id = "ex_run",
            name = "Treadmill Run",
            muscleCategory = MuscleCategory.LEGS,
            description = "Cardio",
            defaultTrackingType = TrackingType.DISTANCE_DURATION,
            modality = ExerciseModality.CARDIO
        )
        val start = 1793678400000L
        val log = WorkoutLog(
            id = "legacy_multimodal",
            userId = "u1",
            templateName = "Multi-Modal",
            date = start,
            startTime = start,
            endTime = start + 42L * 60_000L,
            totalVolume = 400f,
            additionalVolume = 80f,
            dataQuality = "UNVERIFIED",
            measurementVersion = null,
            exerciseLogs = listOf(
                ExerciseLog(
                    exercise = bench,
                    trackingType = TrackingType.WEIGHT_REPS,
                    modality = ExerciseModality.STRENGTH,
                    metricQuality = "UNVERIFIED",
                    sets = listOf(WorkoutSet(setNumber = 1, weightKg = 40.0, reps = 10, isCompleted = true))
                ),
                ExerciseLog(
                    exercise = dips,
                    trackingType = TrackingType.BODYWEIGHT_REPS,
                    modality = ExerciseModality.CALISTHENICS,
                    metricQuality = "UNVERIFIED",
                    sets = listOf(WorkoutSet(setNumber = 1, reps = 8, additionalWeight = 10f, isCompleted = true))
                ),
                ExerciseLog(
                    exercise = pushups,
                    trackingType = TrackingType.REPS_ONLY,
                    modality = ExerciseModality.CALISTHENICS,
                    metricQuality = "UNVERIFIED",
                    sets = listOf(WorkoutSet(setNumber = 1, reps = 55, isCompleted = true))
                ),
                ExerciseLog(
                    exercise = plank,
                    trackingType = TrackingType.DURATION,
                    modality = ExerciseModality.ISOMETRIC,
                    metricQuality = "UNVERIFIED",
                    sets = listOf(WorkoutSet(setNumber = 1, durationSeconds = 45, isCompleted = true))
                ),
                ExerciseLog(
                    exercise = run,
                    trackingType = TrackingType.DISTANCE_DURATION,
                    modality = ExerciseModality.CARDIO,
                    metricQuality = "UNVERIFIED",
                    sets = listOf(WorkoutSet(setNumber = 1, durationSeconds = 1800, distanceMeters = 5000.0, isCompleted = true))
                )
            )
        )

        rule.setContent {
            WorkoutTestEnvironment(indonesian = true) {
                HomeLastWorkout(log = log)
            }
        }

        rule.onNodeWithTag("home_last_workout_card").assertIsDisplayed()
        rule.onNodeWithText("TERAKHIR · MULTI-MODAL").assertIsDisplayed()
        rule.onNodeWithText("Durasi").assertIsDisplayed()
        rule.onNodeWithText("42 mnt").assertIsDisplayed()
        rule.onNodeWithText("45 dtk isometrik").assertIsDisplayed()
        rule.onNodeWithText("Vol. eksternal").assertIsDisplayed()
        rule.onNodeWithText("400 kg").assertIsDisplayed()
        rule.onNodeWithText("80 kg tambahan").assertIsDisplayed()
        rule.onNodeWithText("Jarak").assertIsDisplayed()
        rule.onNodeWithText("5,0 km").assertIsDisplayed()
        rule.onNodeWithText("30 mnt kardio").assertIsDisplayed()
        rule.onNodeWithText("Riwayat lama · 73 repetisi").assertIsDisplayed()
    }

    private fun UserProfileState(isProfileLoadingOverride: Boolean): UserProfileState =
        if (isProfileLoadingOverride) com.diajarkoding.imfit.domain.model.UserProfileState()
        else com.diajarkoding.imfit.domain.model.UserProfileState.unauthenticated()
}

private class FakeHomeUiAuthRepository(initial: UserProfileState) : AuthRepository {
    private val _state = MutableStateFlow(initial)
    override val userProfileState: StateFlow<UserProfileState> = _state

    fun emitProfile(state: UserProfileState) {
        _state.value = state
    }

    override suspend fun refreshProfile(force: Boolean): Result<User?> = Result.success(_state.value.currentUser)
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
        _state.value = UserProfileState.unauthenticated()
    }
    override suspend fun getCurrentUser(): User? = _state.value.currentUser
    override suspend fun getLocalUserId(): String? = _state.value.userId ?: "u1"
    override suspend fun peekLocalUserId(): String? = _state.value.userId ?: "u1"
    override suspend fun isLoggedIn(): Boolean = _state.value.hasRetainedSessionContext
    override suspend fun updateProfile(user: User): Result<User> = Result.success(user)
    override suspend fun getSignedAvatarUrl(storagePath: String?): String? = storagePath
}

private class FakeHomeUiWorkoutRepository(
    private val onFetchLastWorkout: suspend (String) -> HomeRemoteReadResult<WorkoutLog?>
) : WorkoutRepository {
    override suspend fun fetchRemoteLastWorkoutLog(userId: String): HomeRemoteReadResult<WorkoutLog?> =
        onFetchLastWorkout(userId)
    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession =
        throw UnsupportedOperationException()
    override suspend fun getActiveSession(): WorkoutSession? = null
    override suspend fun updateActiveSession(session: WorkoutSession) = Unit
    override suspend fun finishWorkout(): WorkoutLog? = null
    override suspend fun cancelWorkout() = Unit
    override suspend fun updateSessionRestOverride(seconds: Int) = Unit
    override suspend fun getSessionRestOverride(): Int? = null
    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> = emptyList()
    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? = null
    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? = null
}

private class FakeHomeUiRoutineRepository(
    private val onGetRoutines: suspend (String) -> Result<List<WorkoutTemplate>>
) : RoutineRepository {
    override suspend fun getRoutines(userId: String): Result<List<WorkoutTemplate>> = onGetRoutines(userId)
    override suspend fun getRoutineById(routineId: String): Result<WorkoutTemplate?> =
        Result.failure(UnsupportedOperationException())
    override suspend fun createRoutine(userId: String, name: String, requestId: String): Result<WorkoutTemplate> =
        Result.failure(UnsupportedOperationException())
    override suspend fun saveRoutine(
        routineId: String,
        name: String,
        exercises: List<TemplateExercise>,
        expectedRevision: Long?,
        requestId: String,
        baselineExerciseIds: List<String>
    ): Result<WorkoutTemplate> = Result.failure(UnsupportedOperationException())
    override suspend fun deleteRoutine(
        routineId: String,
        expectedRevision: Long?,
        requestId: String,
        baselineExerciseIds: List<String>,
        name: String
    ): Result<Boolean> = Result.failure(UnsupportedOperationException())
}

private class FakeHomeUiLegacyRoutineRecovery(
    private val countsByOwner: Map<String, Int> = emptyMap()
) : LegacyRoutineRecovery {
    var exportCalls = 0
        private set

    override fun observeCount(ownerId: String): Flow<Int> = flowOf(countsByOwner[ownerId] ?: 0)

    override suspend fun export(ownerId: String): String {
        exportCalls++
        error("Presentation tests must not execute filesystem exports")
    }
}
