package com.diajarkoding.imfit.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoSyncStateProvider @Inject constructor() : SyncStateProvider, SyncScheduler, SyncRunner {
    private val state = MutableStateFlow(SyncState())

    override val syncState: StateFlow<SyncState> = state.asStateFlow()

    override fun enqueue(userId: String?) = Unit
    override fun cancel(userId: String) = Unit
    override suspend fun run(expectedUserId: String?) = SyncRunResult.SUCCESS
}
