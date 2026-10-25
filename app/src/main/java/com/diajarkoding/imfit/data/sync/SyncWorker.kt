package com.diajarkoding.imfit.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    // Retire legacy requests after upgrade without network or acknowledging any pending row.
    override suspend fun doWork(): Result = Result.success()

    companion object {
        const val KEY_USER_ID = "user_id"
    }
}
