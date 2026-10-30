package com.diajarkoding.imfit.presentation.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.HomeRemoteReadResult
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.UserProfileState
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.presentation.components.common.ImfitMainAppBar
import com.diajarkoding.imfit.theme.IMFITTheme
import com.diajarkoding.imfit.theme.LocaleManager
import kotlinx.coroutines.CompletableDeferred
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
        val templatesGate = CompletableDeferred<HomeRemoteReadResult<List<WorkoutTemplate>>>()
        val auth = FakeHomeUiAuthRepository(
            UserProfileState(isProfileLoadingOverride = true)
        )
        val workoutRepo = FakeHomeUiWorkoutRepository(
            onFetchTemplates = { templatesGate.await() },
            onFetchLastWorkout = { lastLogGate.await() }
        )
        val viewModel = HomeViewModel(auth, workoutRepo)
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
                        viewModel = viewModel
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
        templatesGate.complete(HomeRemoteReadResult.RemotePrimary(emptyList()))

        rule.waitForIdle()
        rule.onNodeWithTag("home_last_workout_card").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.workout_duration_zero)).assertIsDisplayed()
        rule.onNodeWithText("0 kg").assertIsDisplayed()
    }

    @Test
    fun failedLastWorkoutAndTemplatesShowSectionErrorsInsteadOfZeroOrEmpty() {
        val user = User("u1", "Iskan", "iskan@imfit.com")
        val auth = FakeHomeUiAuthRepository(UserProfileState.fromDirectUser(user))
        var retryCount = 0
        val workoutRepo = FakeHomeUiWorkoutRepository(
            onFetchTemplates = {
                retryCount++
                throw IOException("Network failure")
            },
            onFetchLastWorkout = {
                throw IOException("Network failure")
            }
        )
        val viewModel = HomeViewModel(auth, workoutRepo)

        rule.setContent {
            IMFITTheme {
                HomeScreen(
                    onNavigateToWorkoutDetail = {},
                    viewModel = viewModel
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
    private val onFetchTemplates: suspend (String) -> HomeRemoteReadResult<List<WorkoutTemplate>>,
    private val onFetchLastWorkout: suspend (String) -> HomeRemoteReadResult<WorkoutLog?>
) : WorkoutRepository {
    override suspend fun fetchRemoteTemplates(userId: String): HomeRemoteReadResult<List<WorkoutTemplate>> =
        onFetchTemplates(userId)
    override suspend fun fetchRemoteLastWorkoutLog(userId: String): HomeRemoteReadResult<WorkoutLog?> =
        onFetchLastWorkout(userId)
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
    override suspend fun getActiveSession(): WorkoutSession? = null
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
