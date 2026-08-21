package com.diajarkoding.imfit.data.sync

import kotlinx.coroutines.flow.StateFlow

interface SyncStateProvider {
    val syncState: StateFlow<SyncState>
}
