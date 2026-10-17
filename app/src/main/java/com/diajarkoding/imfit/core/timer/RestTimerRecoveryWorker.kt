package com.diajarkoding.imfit.core.timer

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Recovery only, not the rest deadline scheduler. WorkManager timing is best-effort. */
@Singleton
class RestTimerRecoveryScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : RestTimerRecoveryQueue {
    override suspend fun enqueue(timerId: String, generation: Long) {
        val request = OneTimeWorkRequestBuilder<RestTimerRecoveryWorker>()
            .setInputData(workDataOf(RestTimerRecoveryWorker.KEY_TIMER_ID to timerId,
                RestTimerRecoveryWorker.KEY_GENERATION to generation,
                RestTimerRecoveryWorker.KEY_ENQUEUED_ELAPSED_MS to SystemClock.elapsedRealtime()))
            .setInitialDelay(10, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // Do not cancel the enqueue on receiver cancellation: it is the process-death watchdog.
        RestTimerTrace.measured("recovery_enqueue") {
            val future = WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(timerId, generation), ExistingWorkPolicy.KEEP, request).result
            suspendCancellableCoroutine<Unit> { continuation ->
                future.addListener({
                    try {
                        future.get()
                        continuation.resume(Unit)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }, { runnable -> runnable.run() })
            }
        }
        RestTimerTrace.event("recovery_enqueued", detail = "source=worker restInstanceId=${RestTimerTrace.instanceId(timerId)} generation=$generation")
    }

    override fun cancel(timerId: String, generation: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(timerId, generation))
    }

    private fun workName(timerId: String, generation: Long) = "rest-recovery:$timerId:$generation"
}

@HiltWorker
class RestTimerRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val timer: RestTimerCoordinator
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val timerId = inputData.getString(KEY_TIMER_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val generation = inputData.getLong(KEY_GENERATION, -1).takeIf { it >= 0 } ?: return Result.failure()
        val started = SystemClock.elapsedRealtime()
        val outcome = try {
            withTimeout(RestTimerRecoveryPolicy.WORK_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    // A worker is not an exact-alarm FGS-start exemption. Notification delivery
                    // still works without an Activity/service, but audio must have a legal owner.
                    timer.expiry(timerId, generation, alarmDelivery = false, recoveryDelivery = true)
                }
            }
        } catch (e: TimeoutCancellationException) {
            RestTimerTrace.event("recovery_worker_timeout", timer.state.value,
                "budgetMs=${RestTimerRecoveryPolicy.WORK_TIMEOUT_MS} durationMs=${SystemClock.elapsedRealtime() - started} exception=${e.javaClass.simpleName}")
            AlarmDeliveryResult.RETRY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RestTimerTrace.event("recovery_worker_failed", detail = "exception=${e.javaClass.simpleName}")
            AlarmDeliveryResult.RETRY
        }
        RestTimerTrace.event("recovery_worker_result", timer.state.value,
            "source=worker restInstanceId=${RestTimerTrace.instanceId(timerId)} generation=$generation attempt=${runAttemptCount + 1} result=$outcome durationMs=${SystemClock.elapsedRealtime() - started}")
        return when {
            outcome == AlarmDeliveryResult.HANDLED -> Result.success()
            RestTimerRecoveryPolicy.canRetry(runAttemptCount + 1,
                inputData.getLong(KEY_ENQUEUED_ELAPSED_MS, -1), SystemClock.elapsedRealtime()) -> Result.retry()
            else -> {
                RestTimerTrace.event("recovery_exhausted", timer.state.value,
                    "attempt=${runAttemptCount + 1} reason=RECOVERY_BUDGET_EXHAUSTED")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_TIMER_ID = "timer_id"
        const val KEY_GENERATION = "generation"
        const val KEY_ENQUEUED_ELAPSED_MS = "enqueued_elapsed_ms"
    }
}
