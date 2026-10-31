# Only used with -PrestTestBuildType=release. The test APK must not reference APIs R8 removed
# from the separate tested APK. Do not disable shrinking/optimization of the application.
# Instrumentation-only keep: the AndroidX runner needs kotlin stdlib at runtime. Normal
# release builds without this property remain fully shrunk; document this residual delta.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.Trace { *; }
-keep interface com.diajarkoding.imfit.di.RestTimerEntryPoint { *; }
-keep interface com.diajarkoding.imfit.di.LocalSessionEntryPoint { *; }
-keepclassmembers class com.diajarkoding.imfit.core.timer.RestTimerCoordinator { public *; }
-keepclassmembers class com.diajarkoding.imfit.core.timer.RestTimerAndroidRuntime { public *; }
-keepclassmembers class com.diajarkoding.imfit.core.timer.RestTimerState { public *; }
-keep class com.diajarkoding.imfit.core.timer.RestTimerCorrelation { *; }
-keepclassmembers class com.diajarkoding.imfit.data.local.database.IMFITDatabase { public *; }
-keepclassmembers class com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity { public *; }
-keep interface com.diajarkoding.imfit.data.local.dao.ActiveSessionDao { *; }

# Opt-in authenticated workspace integration calls these real APIs across APKs.
-keep interface com.diajarkoding.imfit.domain.repository.RoutineRepository { *; }
-keep interface com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository { *; }
-keep interface com.diajarkoding.imfit.domain.repository.WorkoutRepository { *; }
-keep interface com.diajarkoding.imfit.data.sync.SyncRunner { *; }
-keep class com.diajarkoding.imfit.data.sync.SyncRunResult { *; }
-keep class com.diajarkoding.imfit.domain.model.** { public *; }
-keep class com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult** { *; }
-keep class com.diajarkoding.imfit.domain.repository.ExerciseCatalogPage { *; }
-keep class com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource { public *; }
-keep class com.diajarkoding.imfit.data.remote.WorkoutAggregateRpcResult { public *; }
-keep class com.diajarkoding.imfit.data.local.entity.** { public *; }
-keep interface com.diajarkoding.imfit.data.local.dao.** { *; }
-keep class io.github.jan.supabase.** { *; }
-keep class kotlinx.serialization.** { *; }
