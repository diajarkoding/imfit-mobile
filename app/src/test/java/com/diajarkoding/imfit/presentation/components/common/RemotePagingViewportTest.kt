package com.diajarkoding.imfit.presentation.components.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePagingViewportTest {
    private val ids = (1..25).associate { "template_$it" to "$it" }

    @Test fun `headers alone do not hint any loaded data`() {
        assertNull(lastVisibleRemoteItem(listOf("greeting", "strength_banner", "last_workout", "workout_section"), ids))
    }

    @Test fun `headers do not shift access to a later data item`() {
        assertEquals("2", lastVisibleRemoteItem(listOf("workout_section", "template_1", "template_2"), ids))
    }

    @Test fun `footer and stale banner do not hint the last loaded item`() {
        assertEquals("2", lastVisibleRemoteItem(listOf("template_2", "remote_error", "page_footer"), ids))
        assertNull(lastVisibleRemoteItem(listOf("page_footer"), ids))
    }

    @Test fun `old query keys and unkeyed placeholders are ignored`() {
        assertNull(lastVisibleRemoteItem(listOf("old_template", 0, "loading"), ids))
        assertNull(lastVisibleRemoteItem(listOf("template_1"), emptyMap()))
        assertNull(lastVisibleRemoteItem(emptyList(), ids))
    }

    @Test fun `data keys map to their stable IDs rather than decorated keys`() {
        assertEquals("25", lastVisibleRemoteItem(listOf("template_24", "template_25", "page_footer"), ids))
        assertEquals("session", lastVisibleRemoteItem(listOf("date", "totals", "session", "footer"), mapOf("session" to "session")))
    }
}
