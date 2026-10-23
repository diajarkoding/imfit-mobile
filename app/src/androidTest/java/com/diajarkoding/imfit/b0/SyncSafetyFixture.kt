package com.diajarkoding.imfit.b0

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.core.timer.*
import com.diajarkoding.imfit.data.local.dao.*
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import com.diajarkoding.imfit.data.repository.AuthRepositoryImpl
import com.diajarkoding.imfit.data.sync.*
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** No production seam: real SDK MockEngine injection + DAO delegation + Room executor probes. */
internal class SyncSafetyFixture private constructor() {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName in setOf("com.diajarkoding.imfit.demo.modaldiagnostic", "com.diajarkoding.imfit.modaldiagnostic")) {
            "B0 runtime fixtures require an isolated diagnostic package"
        }
        check(it.applicationContext.javaClass == android.app.Application::class.java) { "Use the opt-in B0TestRunner" }
    }
    val remote = ScriptedSyncRemote()
    val session = ControlledSession()
    val transactions = TransactionProbeExecutor()
    val insideTransaction = AtomicReference<BlockingProbe?>()
    val db = Room.inMemoryDatabaseBuilder(context, IMFITDatabase::class.java)
        .addCallback(IMFITDatabase.REVISION_CALLBACK)
        .setTransactionExecutor(transactions)
        .setQueryCallback({ sql, _ ->
            if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("exercises")) {
                insideTransaction.getAndSet(null)?.pause()
            }
        }, Executor { it.run() }).build()
    val client = createSupabaseClient("https://phase-b0.invalid", "fixture-not-a-credential") {
        httpEngine = remote.engine
        defaultLogLevel = LogLevel.NONE
        install(Auth) { minimalSettings() }
        install(Postgrest)
        install(Storage)
    }
    val preferences get() = sharedPreferences
    var afterPendingTemplates: suspend () -> Unit = {}
    var afterPendingLogs: suspend () -> Unit = {}
    val templates = object : WorkoutTemplateDao by db.workoutTemplateDao() {
        override suspend fun getPendingSyncTemplates(userId: String): List<WorkoutTemplateEntity> =
            db.workoutTemplateDao().getPendingSyncTemplates(userId).also { afterPendingTemplates() }
    }
    val logs = object : WorkoutLogDao by db.workoutLogDao() {
        override suspend fun getPendingLogs(userId: String): List<WorkoutLogEntity> =
            db.workoutLogDao().getPendingLogs(userId).also { afterPendingLogs() }
    }
    val manager = SyncManager(NetworkMonitor(context), client, preferences, templates,
        db.templateExerciseDao(), logs, db.exerciseLogDao(), db.workoutSetDao(), db.exerciseDao(), db,
        ImfitAggregateRemoteDataSource(client))

    suspend fun owner(owner: String = OWNER_A, event: SessionEvent = SessionEvent.LOGIN) {
        session.transition(owner, event)
        client.auth.importSession(UserSession("fixture-token", "fixture-refresh", expiresIn = 86_400,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = owner)), autoRefresh = false)
    }

    /** Invoke actual private stages, not a second sync algorithm; signatures are checked at runtime. */
    suspend fun stage(name: String, vararg args: Any?): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = SyncManager::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size + 1 }
        method.isAccessible = true
        try { method.invoke(manager, *args, continuation) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    suspend fun seedTemplate(operation: String = "CREATE", children: Boolean = true): WorkoutTemplateEntity {
        val parent = WorkoutTemplateEntity(TEMPLATE, OWNER_A, "Fixture routine", pendingOperation = operation, updatedAt = 100)
        db.workoutTemplateDao().insertTemplate(parent)
        if (children) db.templateExerciseDao().insertTemplateExercises(listOf(
            TemplateExerciseEntity(TEMPLATE, "exercise", 0, 3, 8, 60, pendingOperation = "CREATE", updatedAt = 100)))
        return parent
    }

    suspend fun seedWorkout(): WorkoutLogEntity {
        val parent = WorkoutLogEntity(WORKOUT, OWNER_A, null, "Captured parent", 1, 1, 2, 80f,
            pendingOperation = "CREATE", updatedAt = 100)
        db.workoutLogDao().insertWorkoutLog(parent)
        db.exerciseLogDao().insertExerciseLogs(listOf(ExerciseLogEntity(EXERCISE_LOG, WORKOUT, "exercise", "Fixture", "CHEST",
            0, 80f, 1, 8, pendingOperation = "CREATE")))
        db.workoutSetDao().insertWorkoutSets(listOf(WorkoutSetEntity(SET, EXERCISE_LOG, WORKOUT, "exercise", 1, 10f, 8, true,
            pendingOperation = "CREATE")))
        return parent
    }

    fun authRepository(): AuthRepositoryImpl {
        // Empty synthetic timer, no real alarms/services: stopService is intercepted in this fixture only.
        val clock = object : MonotonicClock {
            override fun elapsedRealtime() = 0L
            override fun epochMillis() = 0L
            override fun bootIdentity() = 1
        }
        val store = object : RestTimerStore {
            override suspend fun read(): RestTimerState? = null
            override suspend fun write(state: RestTimerState) = Unit
        }
        val effects = object : RestTimerEffects {
            override suspend fun validSession(state: RestTimerState) = false
            override fun userStopped(state: RestTimerState) = false
            override fun schedule(state: RestTimerState) = SchedulingMode.UNAVAILABLE
            override fun cancelAlarm(state: RestTimerState) = Unit
            override fun showFinished(state: RestTimerState) = FinishedPresentation.BLOCKED
            override fun dismissFinished() = Unit
            override fun controlsAvailable() = false
            override fun controlsPending() = false
            override fun startAudio() = false
            override fun stopAudio() = Unit
        }
        val cleanupContext = object : ContextWrapper(context) {
            override fun stopService(service: Intent) = false
        }
        val cleanup = TimerSessionCleanup(Provider { RestTimerCoordinator(store, clock, effects) }, cleanupContext)
        return AuthRepositoryImpl(client, object : SyncScheduler {
            override fun enqueue(userId: String?) = Unit
            override fun cancelForUser(userId: String) = Unit
        }, manager, db, preferences, context, cleanup)
    }

    suspend fun close() {
        insideTransaction.getAndSet(null)?.release()
        transactions.close()
        client.close()
        db.close()
    }

    companion object {
        const val OWNER_A = "00000000-0000-0000-0000-000000000001"
        const val OWNER_B = "00000000-0000-0000-0000-000000000002"
        const val TEMPLATE = "00000000-0000-0000-0000-000000000010"
        const val WORKOUT = "00000000-0000-0000-0000-000000000020"
        const val EXERCISE_LOG = "00000000-0000-0000-0000-000000000030"
        const val SET = "00000000-0000-0000-0000-000000000040"
        private val sharedPreferences by lazy { SyncPreferences(InstrumentationRegistry.getInstrumentation().targetContext) }
        suspend fun create(): SyncSafetyFixture {
            val fixture = SyncSafetyFixture()
            // Suppress only this manually constructed instance's startup observer, before importing any identity.
            // Public run/syncAll/master methods are tested explicitly; OS reconnect delivery is not simulated here.
            val field = SyncManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(fixture.manager) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            fixture.client.auth.awaitInitialization()
            fixture.preferences.clearAll()
            fixture.db.openHelper.writableDatabase
            fixture.owner()
            fixture.remote.currentEpoch = { fixture.session.identity.epoch }
            fixture.preferences.setInitialSyncCompleted(OWNER_A, true)
            // A completed marker alone still triggers needsInitialSync when templates are empty and lastSync=0.
            fixture.preferences.setLastSyncTimestamp(OWNER_A, 1L)
            fixture.remote.script = { request ->
                if (request.url.encodedPath.endsWith("rpc/upsert_workout_aggregate"))
                    ScriptedSyncRemote.Reply("""{"workout_id":"$WORKOUT","exercise_count":1,"set_count":1,"server_updated_at":"2026-01-01T00:00:00Z"}""")
                else ScriptedSyncRemote.Reply()
            }
            return fixture
        }
    }
}
