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
    private val syncRunner: SyncRunner
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result = when (
        syncRunner.run(inputData.getString(KEY_USER_ID))
    ) {
        SyncRunResult.SUCCESS,
        SyncRunResult.NO_AUTHENTICATED_USER -> Result.success()
        SyncRunResult.RETRYABLE_FAILURE -> Result.retry()
    }

    companion object {
        const val KEY_USER_ID = "user_id"
    }
}
