package com.diajarkoding.imfit.data.sync

interface SyncScheduler {
    fun enqueue(userId: String?)
    fun cancel(userId: String)
}
