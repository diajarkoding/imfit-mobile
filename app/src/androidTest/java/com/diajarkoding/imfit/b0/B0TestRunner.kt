package com.diajarkoding.imfit.b0

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Opt-in only: prevents Hilt/application startup from creating real network/timer runtimes. */
class B0TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName in setOf("com.diajarkoding.imfit.demo.modaldiagnostic", "com.diajarkoding.imfit.modaldiagnostic"))
        return super.newApplication(cl, Application::class.java.name, context)
    }
}
