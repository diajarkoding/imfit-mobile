package com.diajarkoding.imfit.data.sync

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncScheduler {

    override fun enqueue(userId: String?) {
        // Online-first: no automatic upload queue. Callers retain durable local status;
        // completion makes one explicit attempt and failures require a manual retry.
    }

    override fun cancelForUser(userId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(userId))
    }

    private fun workName(userId: String) = "imfit-sync-$userId"
}
