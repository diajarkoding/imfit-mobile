package com.diajarkoding.imfit.presentation.components.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic list only; no gateway, account, or durable fixture writes. */
class RemotePagingViewportUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun headersFootersAndQueryReplacementNeverInventDataAccess() {
        val rows = mutableStateOf((1..25).map(Int::toString))
        val enabled = mutableStateOf(true)
        val hints = mutableListOf<String>()
        rule.setContent {
            val list = rememberLazyListState()
            RemotePagingViewport(list, rows.value.associate { "row_$it" to it }, hints::add, enabled.value)
            LazyColumn(Modifier.size(240.dp).testTag("viewport"), state = list) {
                item("header") { Box(Modifier.height(300.dp)) { Text("Header") } }
                items(rows.value, key = { "row_$it" }) { id -> Box(Modifier.height(100.dp)) { Text("Row $id") } }
                item("footer") { Box(Modifier.height(300.dp)) { Text("Footer") } }
            }
        }
        rule.runOnIdle { assertTrue("Headers alone must not prefetch data", hints.isEmpty()) }
        rule.onNodeWithTag("viewport").performScrollToIndex(1)
        rule.runOnIdle { assertEquals("3", hints.last()) }
        rule.runOnIdle { enabled.value = false }
        rule.runOnIdle { hints.clear(); enabled.value = true }
        rule.runOnIdle { assertEquals("Same-ID refresh needs a new viewport hint", listOf("3"), hints) }
        rule.onNodeWithTag("viewport").performScrollToIndex(26)
        rule.runOnIdle {
            hints.clear()
            // Same LazyListState, new query keys, old loaded IDs no longer belong to the feed.
            rows.value = listOf("new")
        }
        rule.onNodeWithTag("viewport").performScrollToIndex(1)
        rule.runOnIdle { assertEquals(listOf("new"), hints.distinct()) }
        rule.runOnIdle { hints.clear() }
        rule.onNodeWithTag("viewport").performScrollToIndex(2)
        rule.runOnIdle { assertTrue("A footer-only viewport must not access the last loaded ID", hints.isEmpty()) }
    }
}
