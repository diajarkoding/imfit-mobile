package com.diajarkoding.imfit.core.timer

import android.content.Context
import android.content.Intent
import com.diajarkoding.imfit.core.service.WorkoutService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider

class TimerSessionCleanup @Inject constructor(
    private val timer: Provider<RestTimerCoordinator>,
    @ApplicationContext private val context: Context
) {
    suspend fun stop(reason: String) {
        timer.get().cancel(reason)
        // stopService works even when no Activity is collecting commands.
        context.stopService(Intent(context, WorkoutService::class.java))
    }
}
