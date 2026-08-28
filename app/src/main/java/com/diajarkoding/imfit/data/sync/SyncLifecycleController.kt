package com.diajarkoding.imfit.data.sync

interface SyncLifecycleController {
    suspend fun blockAndAwaitIdle(userId: String)
    fun unblock(userId: String)
}
