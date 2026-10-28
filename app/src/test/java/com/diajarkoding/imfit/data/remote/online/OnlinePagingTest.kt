package com.diajarkoding.imfit.data.remote.online

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.PagingConfig
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlinePagingTest {
    private val auth = OnlineAuth().also { it.signIn("A", "token-A") }
    private data class Row(val id: String)
    private class PageRequest(val query: QueryKey, val cursor: String?, val limit: Int) {
        val result = CompletableDeferred<RemotePage<Row>>()
        fun reply(ids: List<String>, next: String? = null) { result.complete(RemotePage(ids.map(::Row), PageInfo(next, next != null, limit))) }
        fun fail() { result.completeExceptionally(IOException("synthetic failure")) }
    }
    private val requests = Channel<PageRequest>(Channel.UNLIMITED)
    private fun feed(scope: CoroutineScope, dispatcher: CoroutineDispatcher, ignoreCancellation: Boolean = false) =
        RemotePagingFeed(auth, scope, dispatcher, 20, Row::id) { query, cursor, limit ->
            val call = PageRequest(query, cursor, limit)
            requests.send(call)
            if (ignoreCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
    private fun rows(range: IntRange) = range.map(Int::toString)
    private val query = QueryKey.History(DateWindow("2026-10-01", "2026-11-01", "UTC"))

    @Test fun `Paging refresh starts from cursor zero and replaces sixty loaded rows`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler)).use { feed ->
            feed.select(query)
            requests.receive().also { assertNull(it.cursor); assertEquals(20, it.limit); it.reply(rows(1..20), "c1") }
            runCurrent()
            feed.access(19)
            requests.receive().also { assertEquals("c1", it.cursor); it.reply(rows(21..40), "c2") }
            runCurrent(); feed.access(39)
            requests.receive().also { assertEquals("c2", it.cursor); it.reply(rows(41..60)) }
            runCurrent()
            assertEquals(60, feed.state.value.items.size)
            val oldGeneration = feed.state.value.generation
            feed.refresh()
            assertTrue(feed.state.value.stale)
            requests.receive().also { assertNull(it.cursor); it.reply(listOf("new")) }
            runCurrent()
            assertEquals(listOf(Row("new")), feed.state.value.items)
            assertTrue(feed.state.value.generation > oldGeneration)
            assertFalse(feed.state.value.stale)
            assertTrue(feed.state.value.endReached)
        }
    }

    @Test fun `failed refresh retains same query stale snapshot and disables old append hints`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler)).use { feed ->
            feed.select(query)
            requests.receive().reply(rows(1..20), "c1"); runCurrent()
            feed.refresh()
            requests.receive().fail(); runCurrent()
            assertEquals(rows(1..20).map(::Row), feed.state.value.items)
            assertTrue(feed.state.value.stale)
            assertNotNull(feed.state.value.refreshError)
            repeat(10) { feed.access(19) }; runCurrent()
            assertTrue(requests.tryReceive().isFailure)
            feed.retry()
            requests.receive().also { assertNull(it.cursor); it.reply(emptyList()) }; runCurrent()
            assertTrue(feed.state.value.items.isEmpty())
            assertTrue(feed.state.value.hasLoaded)
            assertFalse(feed.state.value.stale)
        }
    }

    @Test fun `append is one in flight and error latches until explicit retry`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler)).use { feed ->
            feed.select(query)
            requests.receive().reply(rows(1..20), "c1"); runCurrent()
            repeat(10) { feed.access(19) }; runCurrent()
            val append = requests.receive()
            assertEquals("c1", append.cursor)
            repeat(10) { feed.access(19) }; runCurrent()
            assertTrue(requests.tryReceive().isFailure)
            append.fail(); runCurrent()
            assertNotNull(feed.state.value.appendError)
            repeat(10) { feed.access(19) }; runCurrent()
            assertTrue(requests.tryReceive().isFailure)
            assertEquals(20, feed.state.value.items.size)
            feed.retry()
            requests.receive().also { assertEquals("c1", it.cursor); it.reply(listOf("20", "21")) }; runCurrent()
            assertEquals(21, feed.state.value.items.size)
            assertEquals(1, feed.state.value.items.count { it.id == "20" })
        }
    }

    @Test fun `initial error and successful empty can refresh without an item`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler)).use { feed ->
            feed.select(query)
            requests.receive().fail(); runCurrent()
            assertFalse(feed.state.value.hasLoaded)
            assertNotNull(feed.state.value.refreshError)
            feed.retry()
            requests.receive().reply(emptyList()); runCurrent()
            assertTrue(feed.state.value.hasLoaded)
            assertTrue(feed.state.value.items.isEmpty())
            assertNull(feed.state.value.refreshError)
            feed.refresh()
            requests.receive().reply(listOf("item")); runCurrent()
            assertEquals(listOf(Row("item")), feed.state.value.items)
        }
    }

    @Test fun `debounced query switch clears old content and only final normalized search loads`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler)).use { feed ->
            feed.select(QueryKey.Catalog("squat", null))
            requests.receive().reply(listOf("old")); runCurrent()
            feed.select(QueryKey.Catalog(normalizeSearch(" B "), null), debounceSearch = true)
            assertTrue(feed.state.value.items.isEmpty())
            runCurrent(); advanceTimeBy(200)
            assertTrue(requests.tryReceive().isFailure)
            feed.select(QueryKey.Catalog(normalizeSearch(" BENCH "), null), debounceSearch = true)
            runCurrent(); advanceTimeBy(299); runCurrent()
            assertTrue(requests.tryReceive().isFailure)
            advanceTimeBy(1); runCurrent()
            requests.receive().also { assertEquals(QueryKey.Catalog("bench", null), it.query); assertNull(it.cursor); it.reply(listOf("bench")) }
            runCurrent()
            assertEquals(listOf(Row("bench")), feed.state.value.items)
        }
    }

    @Test fun `uncancellable old append after refresh cannot publish into replacement generation`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler), ignoreCancellation = true).use { feed ->
            feed.select(query)
            requests.receive().reply(rows(1..20), "c1"); runCurrent()
            feed.access(19)
            val oldAppend = requests.receive()
            feed.refresh()
            // Cancellation can wait for an uncooperative transport; guard still rejects its result.
            oldAppend.reply(listOf("stale-append")); runCurrent()
            requests.receive().reply(listOf("fresh")); runCurrent()
            assertEquals(listOf(Row("fresh")), feed.state.value.items)
        }
    }

    @Test fun `logout synchronously clears PII and A B A cannot publish old response`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler), ignoreCancellation = true).use { feed ->
            feed.select(query)
            requests.receive().reply(listOf("private-A")); runCurrent()
            feed.refresh()
            val old = requests.receive()
            val originalEpoch = auth.capture().identity
            auth.signOut()
            assertTrue(feed.state.value.items.isEmpty())
            auth.signIn("B", "B-token")
            auth.signIn("A", "A-token-new")
            feed.select(query)
            old.reply(listOf("old-A")); runCurrent()
            requests.receive().reply(listOf("new-A")); runCurrent()
            assertEquals(listOf(Row("new-A")), feed.state.value.items)
            assertNotEquals(originalEpoch, feed.state.value.identity)
        }
    }

    @Test fun `new query can finish before delayed old query without mixing lists`() = runTest {
        feed(backgroundScope, StandardTestDispatcher(testScheduler)).use { feed ->
            feed.select(QueryKey.Catalog("squat", null))
            val old = requests.receive()
            feed.select(QueryKey.Catalog("bench", 2))
            requests.receive().reply(listOf("bench")); runCurrent()
            old.reply(listOf("squat")); runCurrent()
            assertEquals(QueryKey.Catalog("bench", 2), feed.state.value.query)
            assertEquals(listOf(Row("bench")), feed.state.value.items)
        }
    }

    @Test fun `source validates full cursor chain metadata limits and dedups by stable id`() = runTest {
        val pages = Channel<RemotePage<Row>>(Channel.UNLIMITED)
        val source = OnlinePagingSource(auth.capture().identity, auth, 20, { true }, Row::id) { _, _ -> pages.receive() }
        pages.send(RemotePage(listOf(Row("a"), Row("a")), PageInfo("c1", true, 20)))
        val first = source.load(PagingSource.LoadParams.Refresh(null, 60, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf(Row("a")), first.data)
        assertNull(first.prevKey)
        assertNull(source.getRefreshKey(PagingState(listOf(first), 0, PagingConfig(20), 0)))
        pages.send(RemotePage(listOf(Row("a"), Row("b")), PageInfo("c2", true, 20)))
        val append = source.load(PagingSource.LoadParams.Append("c1", 20, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf(Row("b")), append.data)
        pages.send(RemotePage(listOf(Row("c")), PageInfo("c1", true, 20)))
        assertTrue(source.load(PagingSource.LoadParams.Append("c2", 20, false)) is PagingSource.LoadResult.Error)
        assertTrue(source.load(PagingSource.LoadParams.Append("foreign-query-cursor", 20, false)) is PagingSource.LoadResult.Error)
        for (info in listOf(PageInfo(null, true, 20), PageInfo("c", false, 20), PageInfo(null, false, 100), PageInfo("", true, 20))) {
            val invalid = OnlinePagingSource(auth.capture().identity, auth, 20, { true }, Row::id) { _, _ -> RemotePage(listOf(Row("a")), info) }
            assertTrue(invalid.load(PagingSource.LoadParams.Refresh(null, 20, false)) is PagingSource.LoadResult.Error)
        }
    }

    @Test fun `source rethrows cancellation and rejects stale generation`() = runTest {
        val source = OnlinePagingSource(auth.capture().identity, auth, 20, { true }, Row::id) { _, _ -> throw CancellationException("cancelled") }
        try { source.load(PagingSource.LoadParams.Refresh(null, 20, false)); fail("Must propagate cancellation") } catch (_: CancellationException) { }
        val stale = OnlinePagingSource(auth.capture().identity, auth, 20, { false }, Row::id) { _, _ -> error("Must not fetch") }
        assertTrue(stale.load(PagingSource.LoadParams.Refresh(null, 20, false)) is PagingSource.LoadResult.Invalid)
    }
}
