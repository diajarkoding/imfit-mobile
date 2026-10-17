# The host selects this class/method by its original name through AndroidJUnitRunner.
-keep class com.diajarkoding.imfit.core.timer.RestTimerProductionReleaseTest { *; }
# The test APK is a host harness, not the shipped product. Keep its runtime intact so the
# minified production app APK remains the only shrinking-sensitive artifact under test.
-dontshrink
-dontoptimize
