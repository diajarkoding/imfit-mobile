package com.diajarkoding.imfit.data.sync

import android.content.Context
import io.mockk.mockk
import io.mockk.confirmVerified
import org.junit.Test

class ManualUploadSchedulingTest {
    @Test fun enqueueNeverStartsWorkManagerForOfflineOrAuthenticatedCallers() {
        val context = mockk<Context>()
        val scheduler = WorkManagerSyncScheduler(context)
        scheduler.enqueue(null)
        repeat(10) { scheduler.enqueue("owner") }
        confirmVerified(context)
    }
}
