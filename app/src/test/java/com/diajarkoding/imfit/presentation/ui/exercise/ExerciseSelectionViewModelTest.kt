package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.SavedStateHandle
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.UserProfileState
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogPage
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSelectionViewModelTest {
    private val bench = exercise("bench")
    private val row = exercise("row", MuscleCategory.BACK)
    private val squat = exercise("squat", MuscleCategory.LEGS)

    @Test fun requestsTenRemoteRowsPerPageAndDeduplicatesAppend() {
        Harness().use { h ->
            h.catalog.fetch = { request ->
                if (request.cursor == null) page(listOf(bench), "next")
                else page(listOf(bench.copy(name = "Updated"), row))
            }
            h.vm.initialize("routine", emptySet(), "Push")
            assertEquals(listOf(Request("", null, null, 10)), h.catalog.requests)
            assertEquals(listOf(bench), h.vm.state.value.allExercises)
            h.vm.loadNextPage()
            assertEquals("next", h.catalog.requests.last().cursor)
            assertEquals(listOf("bench", "row"), h.vm.state.value.allExercises.map { it.id })
            assertFalse(h.vm.state.value.hasMore)
            assertFalse(h.vm.state.value.isAppending)
        }
    }

    @Test fun fullyExcludedPagesAdvanceAtMostThreeTimesPerDemand() {
        Harness().use { h ->
            h.catalog.fetch = { request ->
                val offset = request.cursor?.toInt() ?: 0
                page(listOf(exercise("excluded_$offset")), (offset + 1).toString())
            }
            h.vm.initialize("routine", (0..8).map { "excluded_$it" }.toSet())
            assertEquals(3, h.catalog.requests.size)
            assertTrue(h.vm.state.value.allExercises.isEmpty())
            assertTrue(h.vm.state.value.hasMore)
            assertEquals("3", h.vm.state.value.nextCursor)
            h.vm.loadNextPage()
            assertEquals(6, h.catalog.requests.size)
            assertEquals("6", h.vm.state.value.nextCursor)
            h.vm.loadNextPage()
            assertEquals(9, h.catalog.requests.size)
            h.vm.loadNextPage()
            assertEquals(10, h.catalog.requests.size)
            assertEquals(listOf("excluded_9"), h.vm.state.value.allExercises.map { it.id })
        }
    }

    @Test fun invalidContinuationFailsExplicitlyWithoutDeclaringEndOfCatalog() {
        listOf<String?>(null, "").forEach { invalid ->
            Harness().use { h ->
                h.catalog.fetch = { Result.success(ExerciseCatalogPage(listOf(bench), invalid, true)) }
                h.vm.initialize("routine")
                assertNotNull(h.vm.state.value.errorRes)
                assertFalse(h.vm.state.value.isLoading)
                assertEquals(1, h.catalog.requests.size)
            }
        }
    }

    @Test fun cursorCycleFailsAppendAndRetryKeepsTheFailedContinuation() {
        Harness().use { h ->
            h.catalog.fetch = { request -> if (request.cursor == null) page(listOf(bench), "a") else page(listOf(row), "a") }
            h.vm.initialize("routine")
            h.vm.toggleExercise(bench)
            h.vm.loadNextPage()
            assertNotNull(h.vm.state.value.appendErrorRes)
            assertEquals(listOf(bench), h.vm.state.value.allExercises)
            assertEquals("a", h.vm.state.value.nextCursor)
            h.catalog.fetch = { page(listOf(row)) }
            h.vm.loadNextPage()
            assertEquals("a", h.catalog.requests.last().cursor)
            assertNull(h.vm.state.value.appendErrorRes)
            assertEquals(listOf(bench, row), h.vm.state.value.allExercises)
            assertEquals(listOf(bench), h.vm.state.value.selectedExercises)
        }
    }

    @Test fun appendFailureRetainsRowsSelectionAndSupportsInlineRetry() {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench), "next") }
            h.vm.initialize("routine")
            h.vm.toggleExercise(bench)
            h.catalog.fetch = { Result.failure(IOException("offline")) }
            h.vm.loadNextPage()
            assertNotNull(h.vm.state.value.appendErrorRes)
            assertFalse(h.vm.state.value.isAppending)
            assertEquals(listOf(bench), h.vm.state.value.allExercises)
            assertEquals(listOf(bench), h.vm.state.value.selectedExercises)
            h.catalog.fetch = { page(listOf(row)) }
            h.vm.loadNextPage()
            assertEquals("next", h.catalog.requests.last().cursor)
            assertEquals(listOf(bench, row), h.vm.state.value.allExercises)
        }
    }

    @Test fun appendRetryStartsAtFailureAfterSkippingExcludedPages() {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench), "excluded") }
            h.vm.initialize("routine", setOf("excluded"))
            h.vm.toggleExercise(bench)
            h.catalog.fetch = { request ->
                if (request.cursor == "excluded") page(listOf(exercise("excluded")), "failed")
                else Result.failure(IOException("offline"))
            }
            h.vm.loadNextPage()
            assertEquals("failed", h.vm.state.value.nextCursor)
            assertNotNull(h.vm.state.value.appendErrorRes)
            assertEquals(listOf(bench), h.vm.state.value.allExercises)
            h.catalog.fetch = { page(listOf(row)) }
            h.vm.loadNextPage()
            assertEquals("failed", h.catalog.requests.last().cursor)
            assertEquals(listOf(bench, row), h.vm.state.value.allExercises)
            assertEquals(listOf(bench), h.vm.state.value.selectedExercises)
        }
    }

    @Test fun refreshFailurePreservesDataSelectionAndContinuation() {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench), "next") }
            h.vm.initialize("routine")
            h.vm.toggleExercise(bench)
            h.catalog.fetch = { Result.failure(IOException("offline")) }
            h.vm.pullToRefresh()
            assertNotNull(h.vm.state.value.errorRes)
            assertFalse(h.vm.state.value.isPullRefreshing)
            assertFalse(h.vm.state.value.isAppending)
            assertEquals(listOf(bench), h.vm.state.value.allExercises)
            assertEquals(listOf(bench), h.vm.state.value.selectedExercises)
            assertEquals("next", h.vm.state.value.nextCursor)
        }
    }

    @Test fun repeatedRefreshCoalescesWhilePendingAndKeepsSelectionAfterSuccess() {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench), "next") }
            h.vm.initialize("routine")
            h.vm.toggleExercise(bench)
            var pending: Continuation<Result<ExerciseCatalogPage>>? = null
            h.catalog.fetch = { suspendCoroutine { pending = it } }
            h.vm.pullToRefresh()
            h.vm.pullToRefresh()
            h.vm.pullToRefresh()
            assertEquals(2, h.catalog.requests.size)
            assertTrue(h.vm.state.value.isPullRefreshing)
            pending!!.resume(page(listOf(row)))
            assertFalse(h.vm.state.value.isPullRefreshing)
            assertEquals(listOf(row), h.vm.state.value.allExercises)
            assertEquals(listOf(bench), h.vm.state.value.selectedExercises)
        }
    }

    @Test fun cancelledAppendCannotAppendToNewSearchOrStopItsLoadingState() = runBlocking {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench), "next") }
            h.vm.initialize("routine")
            h.vm.toggleExercise(bench)
            var oldAppend: Continuation<Result<ExerciseCatalogPage>>? = null
            h.catalog.fetch = { suspendCoroutine { oldAppend = it } }
            h.vm.loadNextPage()
            h.vm.onSearchQueryChange("s")
            h.vm.onSearchQueryChange("sq")
            h.vm.onSearchQueryChange("squat")
            oldAppend!!.resume(page(listOf(row)))
            assertTrue(h.vm.state.value.isLoading)
            assertFalse(h.vm.state.value.isAppending)
            assertTrue(h.vm.state.value.allExercises.isEmpty())
            assertEquals(2, h.catalog.requests.size)
            h.catalog.fetch = { page(listOf(squat)) }
            delay(350)
            assertEquals(3, h.catalog.requests.size)
            assertEquals("squat", h.catalog.requests.last().search)
            assertNull(h.catalog.requests.last().cursor)
            assertEquals(listOf(squat), h.vm.state.value.allExercises)
            assertEquals(listOf(bench), h.vm.state.value.selectedExercises)
        }
    }

    @Test fun oldRefreshFailureCannotClearSpinnerOrDataOfNewGeneration() {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench)) }
            h.vm.initialize("routine")
            var oldRefresh: Continuation<Result<ExerciseCatalogPage>>? = null
            h.catalog.fetch = { suspendCoroutine { oldRefresh = it } }
            h.vm.pullToRefresh()
            h.catalog.fetch = { page(listOf(row)) }
            h.vm.selectCategory(ExerciseCategory.BACK)
            var newRefresh: Continuation<Result<ExerciseCatalogPage>>? = null
            h.catalog.fetch = { suspendCoroutine { newRefresh = it } }
            h.vm.pullToRefresh()
            oldRefresh!!.resume(Result.failure(IOException("old failure")))
            assertTrue(h.vm.state.value.isPullRefreshing)
            assertNull(h.vm.state.value.errorRes)
            assertEquals(listOf(row), h.vm.state.value.allExercises)
            newRefresh!!.resume(page(listOf(row.copy(name = "Fresh row"))))
            assertFalse(h.vm.state.value.isPullRefreshing)
            assertEquals("Fresh row", h.vm.state.value.allExercises.single().name)
            assertEquals(ExerciseCategory.BACK.databaseId, h.catalog.requests.last().categoryId)
        }
    }

    @Test fun recreationRestoresQueryFilterAndOrderedSelectionByTargetedHydration() {
        val saved = SavedStateHandle()
        Harness(saved).use { h ->
            h.catalog.fetch = { page(listOf(bench, row)) }
            h.vm.initialize("routine", selectionContextId = "flow")
            h.vm.toggleExercise(row)
            h.vm.toggleExercise(bench)
            h.vm.selectCategory(ExerciseCategory.BACK)
            h.vm.onSearchQueryChange("row")
        }
        Harness(saved).use { recreated ->
            recreated.catalog.hydrate = { ids ->
                assertEquals(listOf("row", "bench"), ids)
                ExerciseHydrationResult.Success(listOf(bench, row))
            }
            recreated.catalog.fetch = { page(listOf(row)) }
            recreated.vm.initialize("routine", selectionContextId = "flow")
            assertEquals("row", recreated.vm.state.value.searchQuery)
            assertEquals(ExerciseCategory.BACK, recreated.vm.state.value.selectedCategory)
            assertEquals(listOf(row, bench), recreated.vm.state.value.selectedExercises)
            assertEquals(Request("row", ExerciseCategory.BACK.databaseId, null, 10), recreated.catalog.requests.single())
        }
    }

    @Test fun missingRestoredSelectionShowsErrorAndRetriesHydrationWithoutSilentlyDroppingIds() {
        val saved = SavedStateHandle(mapOf("picker.owner" to "owner", "picker.context" to "flow",
            "picker.selection" to arrayListOf("bench", "row")))
        Harness(saved).use { h ->
            h.catalog.hydrate = { ExerciseHydrationResult.Missing(listOf(bench), listOf("row")) }
            h.vm.initialize("routine", selectionContextId = "flow")
            assertNotNull(h.vm.state.value.errorRes)
            assertTrue(h.catalog.requests.isEmpty())
            h.catalog.hydrate = { ExerciseHydrationResult.Success(listOf(row, bench)) }
            h.catalog.fetch = { page(listOf(bench)) }
            h.vm.retry()
            assertEquals(listOf(bench, row), h.vm.state.value.selectedExercises)
            assertNull(h.vm.state.value.errorRes)
        }
    }

    @Test fun differentOwnerOrFlowCannotRestoreAnotherSelection() {
        listOf("different_owner" to "flow", "owner" to "different_flow").forEach { (owner, flow) ->
            val saved = SavedStateHandle(mapOf("picker.owner" to "owner", "picker.context" to "flow",
                "picker.selection" to arrayListOf("bench"), "picker.query" to "bench"))
            Harness(saved).use { h ->
                h.auth.owner = owner
                h.vm.initialize("routine", selectionContextId = flow)
                assertTrue(h.vm.state.value.selectedExercises.isEmpty())
                assertEquals("", h.vm.state.value.searchQuery)
                assertTrue(h.catalog.hydrationRequests.isEmpty())
            }
        }
    }

    @Test fun unsupportedAndInactiveExercisesRemainVisibleButCannotBeSelected() {
        Harness().use { h ->
            val plank = exercise("ex_core_1", MuscleCategory.CORE)
            val inactive = bench.copy(isActive = false)
            h.catalog.fetch = { page(listOf(plank, inactive, row)) }
            h.vm.initialize("session", setOf("already_in_session"))
            h.vm.toggleExercise(plank)
            h.vm.toggleExercise(inactive)
            h.vm.toggleExercise(row)
            assertEquals(listOf(plank, inactive, row), h.vm.state.value.allExercises)
            assertEquals(listOf(row), h.vm.state.value.selectedExercises)
        }
    }

    @Test fun accountSwitchRejectsPendingResponseAndClearsSelection() {
        Harness().use { h ->
            h.catalog.fetch = { page(listOf(bench), "next") }
            h.vm.initialize("routine")
            h.vm.toggleExercise(bench)
            var pending: Continuation<Result<ExerciseCatalogPage>>? = null
            h.catalog.fetch = { suspendCoroutine { pending = it } }
            h.vm.loadNextPage()
            h.auth.owner = "another_owner"
            pending!!.resume(page(listOf(row)))
            assertTrue(h.vm.state.value.allExercises.isEmpty())
            assertTrue(h.vm.state.value.selectedExercises.isEmpty())
            assertFalse(h.vm.state.value.isAppending)
            assertNull(h.saved.get<String>("picker.owner"))
        }
    }

    private class Harness(val saved: SavedStateHandle = SavedStateHandle()) : Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val auth = PickerAuth()
        val catalog = Catalog()
        val vm = ExerciseSelectionViewModel(catalog, auth, saved, scope)
        override fun close() { scope.cancel() }
    }

    private data class Request(val search: String, val categoryId: Int?, val cursor: String?, val limit: Int)

    private class Catalog : ExerciseCatalogRepository {
        val requests = mutableListOf<Request>()
        val hydrationRequests = mutableListOf<List<String>>()
        var fetch: suspend (Request) -> Result<ExerciseCatalogPage> = { page(emptyList()) }
        var hydrate: suspend (List<String>) -> ExerciseHydrationResult = { ExerciseHydrationResult.Success(emptyList()) }
        override suspend fun fetchCatalogPage(search: String, categoryId: Int?, cursor: String?, limit: Int): Result<ExerciseCatalogPage> {
            val request = Request(search, categoryId, cursor, limit)
            requests += request
            return fetch(request)
        }
        override suspend fun getExercisesByIds(ids: List<String>): ExerciseHydrationResult {
            hydrationRequests += ids
            return hydrate(ids)
        }
    }

    private class PickerAuth : AuthRepository {
        var owner: String? = "owner"
        override val userProfileState = MutableStateFlow(UserProfileState.fromDirectUser(null))
        override suspend fun getLocalUserId() = owner
        override suspend fun peekLocalUserId() = owner
        override suspend fun getCurrentUser(): User? = null
        override suspend fun refreshProfile(force: Boolean): Result<User?> = Result.success(null)
        override suspend fun register(name: String, email: String, password: String, birthDate: String?, profilePhotoUri: String?): Result<RegisterResult> = error("Unused")
        override suspend fun login(email: String, password: String): Result<User> = error("Unused")
        override suspend fun logout() { owner = null }
        override suspend fun isLoggedIn() = owner != null
        override suspend fun updateProfile(user: User): Result<User> = error("Unused")
        override suspend fun getSignedAvatarUrl(storagePath: String?): String? = null
    }

    private companion object {
        fun exercise(id: String, category: MuscleCategory = MuscleCategory.CHEST) = Exercise(id, id, category, "")
        fun page(rows: List<Exercise>, next: String? = null) = Result.success(ExerciseCatalogPage(rows, next, next != null))
    }
}
