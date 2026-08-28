package com.diajarkoding.imfit.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncScheduler {

    override fun enqueue(userId: String?) {
        if (userId == null) return

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(Data.Builder().putString(SyncWorker.KEY_USER_ID, userId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(userId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    override fun cancelForUser(userId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(userId))
    }

    private fun workName(userId: String) = "imfit-sync-$userId"
}
