package com.diajarkoding.imfit.data.remote.online

import androidx.paging.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Query identity includes all filters, not the opaque cursor or current loaded page. */
sealed interface QueryKey {
    data class History(val window: DateWindow) : QueryKey
    data class Catalog(val search: String, val categoryId: Int?, val exerciseIds: List<String>? = null) : QueryKey {
        init { require(search == normalizeSearch(search) && search.length <= 100) }
    }
    data object Templates : QueryKey
}

class CursorProtocolException : Exception("Invalid pagination metadata")

class OnlinePagingSource<T : Any>(
    private val identity: AuthIdentity,
    private val auth: OnlineAuth,
    private val pageSize: Int,
    private val isGenerationCurrent: () -> Boolean,
    private val stableId: (T) -> String,
    private val fetch: suspend (String?, Int) -> RemotePage<T>,
) : PagingSource<String, T>() {
    private val seenIds = mutableSetOf<String>()
    private val issuedCursors = mutableSetOf<String>()
    private val consumedCursors = mutableSetOf<String>()
    override fun getRefreshKey(state: PagingState<String, T>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, T> {
        if (invalid || !isGenerationCurrent()) return LoadResult.Invalid()
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        return try {
            val cursor = if (params is LoadParams.Refresh) null else params.key
            if (cursor != null && (cursor !in issuedCursors || cursor in consumedCursors)) throw CursorProtocolException()
            // Paging initial/append loadSize is advisory; never exceed the backend contract.
            val page = fetch(cursor, pageSize)
            currentCoroutineContext().ensureActive()
            if (invalid || !isGenerationCurrent()) return LoadResult.Invalid()
            if (!auth.isCurrent(identity)) throw StaleAuthException()
            val info = page.pageInfo
            if (info.limit != pageSize || page.data.size > pageSize ||
                info.hasMore != (info.nextCursor != null) ||
                (info.hasMore && (page.data.isEmpty() || info.nextCursor.isNullOrBlank() ||
                    info.nextCursor == cursor || info.nextCursor in issuedCursors))) throw CursorProtocolException()
            val ids = page.data.map(stableId)
            if (ids.any(String::isBlank)) throw CursorProtocolException()
            val unique = page.data.distinctBy(stableId).filter { stableId(it) !in seenIds }
            // Prevent an unbounded automatic prefetch loop on all-duplicate nonterminal pages.
            if (unique.isEmpty() && info.hasMore) throw CursorProtocolException()
            seenIds += ids
            cursor?.let { consumedCursors += it }
            info.nextCursor?.let { issuedCursors += it }
            LoadResult.Page(unique, prevKey = null, nextKey = info.nextCursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LoadResult.Error(failure)
        }
    }
}

data class RemoteListState<T>(
    val query: QueryKey? = null,
    val identity: AuthIdentity? = null,
    val generation: Long = 0,
    val items: List<T> = emptyList(),
    val hasLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val stale: Boolean = false,
    val endReached: Boolean = false,
    val refreshError: Throwable? = null,
    val appendError: Throwable? = null,
)

/**
 * Spike presentation adapter over Paging, NOT a second paginator. Paging owns cursors/retry/load
 * serialization. Only query-scoped display snapshots are retained across failed refreshes.
 * Future ViewModels own this with viewModelScope. Call select/refresh/access/retry on main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemotePagingFeed<T : Any>(
    private val auth: OnlineAuth,
    parentScope: CoroutineScope,
    private val mainContext: CoroutineContext = Dispatchers.Main,
    private val pageSize: Int,
    private val stableId: (T) -> String,
    private val fetch: suspend (QueryKey, String?, Int) -> RemotePage<T>,
) : AutoCloseable {
    private data class Binding(val query: QueryKey, val identity: AuthIdentity, val generation: Long, val debounceMs: Long)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + mainContext)
    private val sequence = AtomicLong()
    private val binding = MutableStateFlow<Binding?>(null)
    private val mutableState = MutableStateFlow(RemoteListState<T>())
    val state = mutableState.asStateFlow()
    private var presenter: PagingDataPresenter<T>? = null
    private val authSubscription = auth.onIdentityChanged {
        sequence.incrementAndGet()
        binding.value = null
        presenter = null
        mutableState.value = RemoteListState()
    }

    init {
        require(pageSize in 1..100)
        scope.launch {
            binding.flatMapLatest { selected ->
                flow<Unit> {
                    if (selected != null) {
                        delay(selected.debounceMs)
                        collectGeneration(selected)
                    }
                }
            }.collect()
        }
    }

    fun select(query: QueryKey, debounceSearch: Boolean = false) {
        val identity = auth.capture().identity
        val old = binding.value
        if (old?.query == query && old.identity == identity) return
        start(query, identity, retain = false, debounceMs = if (debounceSearch) 300 else 0)
    }
    fun refresh() {
        val current = binding.value ?: return
        auth.withCurrent(current.identity) { start(current.query, current.identity, retain = true, debounceMs = 0) }
    }
    fun retry() {
        val current = state.value
        val protocol = current.appendError is CursorProtocolException ||
            (current.appendError as? OnlineFailure)?.reason.let {
                it == RemoteError.Protocol || (it is RemoteError.Validation && it.discriminator == "INVALID_CURSOR")
            }
        if (current.refreshError != null || protocol) refresh()
        else if (current.appendError != null) presenter?.retry()
    }
    fun access(index: Int) {
        val current = state.value
        // A stale display must not feed hints into a previous/failed generation.
        if (current.refreshing || current.refreshError != null || current.appendError != null || current.stale) return
        if (index in current.items.indices) presenter?.get(index)
    }
    override fun close() {
        sequence.incrementAndGet()
        authSubscription.close()
        scope.cancel()
        binding.value = null
        presenter = null
        mutableState.value = RemoteListState()
    }

    private fun start(query: QueryKey, identity: AuthIdentity, retain: Boolean, debounceMs: Long) {
        auth.withCurrent(identity) {
            val generation = sequence.incrementAndGet()
            val old = state.value
            val keep = retain && old.identity == identity && old.query == query && old.hasLoaded
            presenter = null
            mutableState.value = RemoteListState(query, identity, generation,
                items = if (keep) old.items else emptyList(), hasLoaded = keep,
                refreshing = true, stale = keep)
            binding.value = Binding(query, identity, generation, debounceMs)
        }
    }
    private fun current(selected: Binding) = binding.value == selected && sequence.get() == selected.generation && auth.isCurrent(selected.identity)

    private suspend fun collectGeneration(selected: Binding) = coroutineScope {
        if (!current(selected)) return@coroutineScope
        val display = object : PagingDataPresenter<T>(mainContext) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) {
                publish(selected) { it.copy(items = snapshot().items, hasLoaded = true, stale = false) }
            }
        }
        presenter = display
        display.addLoadStateListener { loads ->
            publish(selected) { old -> old.copy(
                refreshing = loads.refresh is LoadState.Loading,
                appending = loads.append is LoadState.Loading,
                refreshError = (loads.refresh as? LoadState.Error)?.error,
                appendError = (loads.append as? LoadState.Error)?.error,
                stale = old.stale || (loads.refresh is LoadState.Error && old.hasLoaded),
                endReached = loads.append.endOfPaginationReached,
            ) }
        }
        Pager(PagingConfig(pageSize = pageSize, initialLoadSize = pageSize, prefetchDistance = 5, enablePlaceholders = false)) {
            OnlinePagingSource(selected.identity, auth, pageSize, { current(selected) }, stableId) { cursor, limit ->
                fetch(selected.query, cursor, limit)
            }
        }.flow.cachedIn(this).collectLatest { display.collectFrom(it) }
    }
    private fun publish(selected: Binding, transform: (RemoteListState<T>) -> RemoteListState<T>) {
        if (!current(selected)) return
        auth.withCurrent(selected.identity) { if (current(selected)) mutableState.value = transform(mutableState.value) }
    }
}

fun OnlineRepository.catalogFeed(scope: CoroutineScope, mainContext: CoroutineContext = Dispatchers.Main) =
    RemotePagingFeed(auth, scope, mainContext, 25, ExerciseSummary::id) { query, cursor, limit ->
        require(query is QueryKey.Catalog)
        execute(OnlineRpc.catalog(query.search, query.categoryId, cursor, limit))
    }

fun OnlineRepository.historyFeed(scope: CoroutineScope, mainContext: CoroutineContext = Dispatchers.Main) =
    RemotePagingFeed(auth, scope, mainContext, 20, WorkoutSummary::id) { query, cursor, limit ->
        require(query is QueryKey.History)
        execute(OnlineRpc.history(query.window, cursor, limit))
    }

fun OnlineRepository.templateFeed(scope: CoroutineScope, mainContext: CoroutineContext = Dispatchers.Main) =
    RemotePagingFeed(auth, scope, mainContext, 15, TemplateSummary::id) { query, cursor, limit ->
        require(query == QueryKey.Templates)
        execute(OnlineRpc.templates(cursor, limit))
    }
