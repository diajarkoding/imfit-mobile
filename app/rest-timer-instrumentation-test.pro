# The host selects this class/method by its original name through AndroidJUnitRunner.
-keep class com.diajarkoding.imfit.core.timer.RestTimerProductionReleaseTest { *; }
# The test APK is a host harness, not the shipped product. Keep its runtime intact so the
# minified production app APK remains the only shrinking-sensitive artifact under test.
-dontshrink
-dontoptimize
# Core-library desugaring is packaged by a separate L8 task, not by these R8 rules.
# build.gradle.kts retains the full j$ runtime in this opt-in harness so it cannot shadow
# application-owned APIs with a test-only partial runtime.
