package com.diajarkoding.imfit.presentation.ui.exercise

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Synthetic catalog only: no account, repository writes, or device preference changes. */
class ExerciseListScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Scene(
        val state: ExerciseBrowserState = ExerciseBrowserState(results = listOf(bench, row)),
        val category: ExerciseCategory = ExerciseCategory.CHEST,
        val dark: Boolean = false,
        val indonesian: Boolean = true,
        val width: Int = 412,
        val fontScale: Float = 1f
    )
    private val scene = mutableStateOf(Scene())
    private var backCalls = 0
    private var retryCalls = 0
    private val searches = mutableListOf<String>()

    @Test fun contentMatchesPickerFamilyAcrossThemesLocalesAndCompactViewport() {
        show()
        for (dark in listOf(false, true)) for (indonesian in listOf(true, false)) for (compact in listOf(false, true)) {
            rule.runOnIdle { scene.value = Scene(dark = dark, indonesian = indonesian,
                width = if (compact) 320 else 412, fontScale = if (compact) 1.5f else 1f) }
            rule.onNodeWithContentDescription(text(R.string.action_back)).assertIsDisplayed()
            rule.onNodeWithTag("exercise_list_search").assertIsDisplayed()
            rule.onNodeWithTag("exercise_list_row_bench").assertIsDisplayed().assertHasNoClickAction()
            rule.onNodeWithTag("exercise_list_row_row").assertDoesNotExist()
            rule.onNode(isToggleable()).assertDoesNotExist()
            val search = rule.onNodeWithTag("exercise_list_search").fetchSemanticsNode().boundsInRoot
            val item = rule.onNodeWithTag("exercise_list_row_bench").fetchSemanticsNode().boundsInRoot
            assertEquals("Search and cards share the same gutter", search.left, item.left, 1f)
            assertTrue("Results remain below search", item.top >= search.bottom)
            assertMinimumTarget(rule.onNodeWithContentDescription(text(R.string.action_back)))
            assertNoTextOverflow(bench.name)
            assertNoTextOverflow(bench.description)
            capture("content-${if (dark) "dark" else "light"}-${if (indonesian) "id" else "en"}-${if (compact) "320-font150" else "412"}")
        }
    }

    @Test fun loadingFailureAndThreeEmptyStatesRemainDistinct() {
        show()
        rule.runOnIdle { scene.value = Scene(state = ExerciseBrowserState(isLoading = true, hasError = true, results = listOf(bench))) }
        rule.onNodeWithTag("exercise_list_loading").assertExists()
        rule.onNodeWithTag("exercise_list_error").assertDoesNotExist()
        rule.onNodeWithTag("exercise_list_row_bench").assertDoesNotExist()
        capture("loading")

        rule.runOnIdle { scene.value = Scene(state = ExerciseBrowserState(hasError = true, query = "bench", results = listOf(bench))) }
        rule.onNodeWithTag("exercise_list_error").assertExists()
        rule.onNodeWithTag("exercise_list_empty").assertDoesNotExist()
        rule.onNodeWithTag("exercise_list_row_bench").assertDoesNotExist()
        rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        rule.runOnIdle { assertEquals(1, retryCalls); assertEquals("bench", scene.value.state.query) }
        capture("failure-retains-query")

        rule.runOnIdle { scene.value = Scene(state = ExerciseBrowserState()) }
        rule.onNodeWithText(text(R.string.exercise_no_results)).assertIsDisplayed()
        capture("category-empty")
        rule.runOnIdle { scene.value = Scene(state = ExerciseBrowserState(query = "no match")) }
        rule.onNodeWithText(text(R.string.redesign_no_results)).assertIsDisplayed()
        capture("search-empty")
        rule.runOnIdle { scene.value = Scene(state = ExerciseBrowserState(), category = ExerciseCategory.FOREARMS) }
        rule.onNodeWithText(text(R.string.exercise_subgroup_empty)).assertIsDisplayed()
        capture("subgroup-empty")
    }

    @Test fun searchClearRetryAndBackKeepTheirExistingOwners() {
        show()
        rule.onNodeWithTag("exercise_list_search").performTextInput("bench")
        rule.runOnIdle { assertEquals(listOf("bench"), searches) }
        val clear = rule.onNodeWithContentDescription(text(R.string.action_clear))
        assertMinimumTarget(clear)
        clear.performClick()
        rule.runOnIdle { assertEquals("", searches.last()) }
        rule.onNodeWithContentDescription(text(R.string.action_clear)).assertDoesNotExist()
        rule.runOnIdle { scene.value = scene.value.copy(state = ExerciseBrowserState(query = "missing")) }
        rule.onNodeWithText(text(R.string.action_clear)).performClick()
        rule.runOnIdle { assertEquals("", searches.last()) }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test fun categoryMappingDeduplicationAndReadOnlyRowsArePreserved() {
        val calf = Exercise("ex_legs_6", "Calf Raises", MuscleCategory.LEGS, "")
        val glute = Exercise("ex_legs_3", "Romanian Deadlift", MuscleCategory.LEGS, "")
        val catalog = listOf(bench, bench.copy(name = "Duplicate must not render"), row, calf, glute)
        scene.value = Scene(state = ExerciseBrowserState(results = catalog))
        show()
        for ((category, expected) in listOf(
            ExerciseCategory.CHEST to listOf(bench),
            ExerciseCategory.BACK to listOf(row),
            ExerciseCategory.LEGS to listOf(glute),
            ExerciseCategory.GLUTES to listOf(glute),
            ExerciseCategory.CALVES to listOf(calf),
            ExerciseCategory.ALL to listOf(bench, row, calf, glute)
        )) {
            rule.runOnIdle { scene.value = scene.value.copy(category = category) }
            rule.onAllNodesWithTag("exercise_list_row_bench").assertCountEquals(if (bench in expected) 1 else 0)
            rule.onNodeWithText("Duplicate must not render").assertDoesNotExist()
            for (exercise in expected) {
                rule.onNodeWithTag("exercise_list").performScrollToNode(hasTestTag("exercise_list_row_${exercise.id}"))
                rule.onNodeWithTag("exercise_list_row_${exercise.id}").assertIsDisplayed().assertHasNoClickAction()
            }
        }
    }

    @Test fun longNamesAndDescriptionsWrapAt320AndFont150WithoutHidingLastRow() {
        val long = bench.copy(name = "Incline Alternating Dumbbell Chest Press with Neutral Grip",
            description = "Keep your shoulders stable and lower both dumbbells slowly before pressing upward with control.")
        scene.value = Scene(width = 320, fontScale = 1.5f, state = ExerciseBrowserState(results =
            listOf(long) + (1..12).map { bench.copy(id = "bench_$it", name = "Bench Press $it") }))
        show()
        assertNoTextOverflow(long.name)
        assertNoTextOverflow(long.description)
        capture("long-text-320-font150")
        rule.onNodeWithTag("exercise_list").performScrollToNode(hasTestTag("exercise_list_row_bench_12"))
        rule.onNodeWithTag("exercise_list_row_bench_12").assertIsDisplayed()
        rule.onNodeWithTag("exercise_list_search").assertIsDisplayed()
        assertMinimumTarget(rule.onNodeWithContentDescription(text(R.string.action_back)))
        capture("last-row-320-font150")
    }

    private fun show() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent { Fixture() }
    }

    @Composable
    private fun Fixture() {
        val value = scene.value
        val base = LocalConfiguration.current
        val context = LocalContext.current
        val config = remember(base, value.indonesian, value.width, value.fontScale) {
            Configuration(base).apply {
                setLocale(Locale.forLanguageTag(if (value.indonesian) "id-ID" else "en-US"))
                screenWidthDp = value.width
                fontScale = value.fontScale
            }
        }
        val resources = remember(context, config) { context.createConfigurationContext(config).resources }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalConfiguration provides config, LocalResources provides resources,
                LocalDensity provides Density(constraints.maxWidth.toFloat() / value.width, value.fontScale)) {
                IMFITTheme(darkTheme = value.dark) {
                    ExerciseListContent(value.category, value.state, onBack = { backCalls++ }, onRetry = { retryCalls++ },
                        onSearch = { query -> searches.add(query); scene.value = scene.value.copy(state = scene.value.state.copy(query = query)) })
                }
            }
        }
    }

    private fun text(id: Int): String {
        val config = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(if (scene.value.indonesian) "id-ID" else "en-US"))
        }
        return rule.activity.createConfigurationContext(config).getString(id)
    }

    private fun assertMinimumTarget(node: SemanticsNodeInteraction) {
        val density = rule.onNodeWithTag("exercise_list").fetchSemanticsNode().boundsInRoot.width / (scene.value.width - 40)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Minimum 48 dp target", bounds.width + 1 >= 48 * density && bounds.height + 1 >= 48 * density)
    }

    private fun assertNoTextOverflow(text: String) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
        assertTrue(results.isNotEmpty())
        results.forEach { assertFalse("Text must wrap without clipping: $text; size=${it.size}, constraints=${it.layoutInput.constraints}, " +
            "widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, viewport=${scene.value.width}, fontScale=${scene.value.fontScale}", it.hasVisualOverflow) }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = File(output ?: instrumentation.targetContext.filesDir.absolutePath, "exercise-list-redesign-evidence").apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private val bench = Exercise("bench", "Barbell Bench Press", MuscleCategory.CHEST, "Press the barbell upward with control.")
        private val row = Exercise("row", "Barbell Row", MuscleCategory.BACK, "Pull the barbell toward your torso.")
    }
}
