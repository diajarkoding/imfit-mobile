package com.diajarkoding.imfit.core.timer

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.diajarkoding.imfit.BuildConfig

/** Boundary events only; elapsed time is authoritative for latency measurements. */
object RestTimerTrace {
    fun event(event: String, state: RestTimerState? = null, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        Log.d("IMFITRest", "event=$event wallMs=${System.currentTimeMillis()} api=${android.os.Build.VERSION.SDK_INT} pid=${Process.myPid()} now=$now " +
            "timer=${state?.timerId} generation=${state?.generation} status=${state?.status} " +
            "boot=${state?.bootIdentity} deadline=${state?.deadlineElapsedMs} " +
            "remainingMs=${state?.let { (it.deadlineElapsedMs - now).coerceAtLeast(0) }} " +
            "latenessMs=${state?.let { (now - it.deadlineElapsedMs).coerceAtLeast(0) }} $detail")
    }
}
