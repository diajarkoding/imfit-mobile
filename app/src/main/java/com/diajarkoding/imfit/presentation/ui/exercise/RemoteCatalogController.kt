package com.diajarkoding.imfit.presentation.ui.exercise

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class RemoteCatalogController(private val gateway: RemoteReadGateway, scope: CoroutineScope) : AutoCloseable {
    val feed = gateway.catalogFeed(scope)
    private var category = ExerciseCategory.ALL
    private var search = ""
    private var active = false
    init {
        scope.launch { gateway.auth.identity.collectLatest { if (it != null && active) select(category, search) } }
        // Initial sync reads session identity only, never profile/Room.
        runCatching { gateway.auth.capture() }
    }
    fun select(category: ExerciseCategory, search: String, debounce: Boolean = false) {
        this.category = category
        this.search = search
        active = true
        if (gateway.auth.identity.value == null) return
        val id = when (category) {
            ExerciseCategory.CHEST -> 1; ExerciseCategory.BACK -> 2; ExerciseCategory.SHOULDERS -> 3
            ExerciseCategory.BICEPS -> 4; ExerciseCategory.TRICEPS -> 5; ExerciseCategory.LEGS -> 6
            ExerciseCategory.CORE -> 7; ExerciseCategory.CARDIO -> 8; else -> null
        }
        val ids = when (category) {
            ExerciseCategory.FOREARMS -> emptyList()
            ExerciseCategory.GLUTES -> listOf("ex_legs_3", "ex_legs_7")
            ExerciseCategory.CALVES -> listOf("ex_legs_6")
            else -> null
        }
        feed.select(QueryKey.Catalog(normalizeSearch(search).take(100), id, ids), debounce)
    }
    fun refresh() = feed.refresh()
    fun retry() = feed.retry()
    fun access(id: String) {
        val index = feed.state.value.items.indexOfFirst { it.id == id }
        if (index >= 0) feed.access(index)
    }
    override fun close() = feed.close()
}
