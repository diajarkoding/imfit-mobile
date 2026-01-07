# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== SUPABASE / KTOR =====
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.utils.io.**
-dontwarn io.ktor.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-keepattributes Signature
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ===== KOTLINX SERIALIZATION =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.diajarkoding.imfit.**$$serializer { *; }
-keepclassmembers class com.diajarkoding.imfit.** {
    *** Companion;
}
-keepclasseswithmembers class com.diajarkoding.imfit.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== ROOM =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ===== HILT =====
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ===== COIL =====
-dontwarn coil.**

# ===== COMPOSE =====
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ===== KEEP DATA CLASSES FOR SERIALIZATION =====
-keep class com.diajarkoding.imfit.data.remote.dto.** { *; }
-keep class com.diajarkoding.imfit.domain.model.** { *; }