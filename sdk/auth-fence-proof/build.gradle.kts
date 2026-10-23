import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

group = "com.diajarkoding.imfit.proof"
version = "3.0.3-b11-proof.1"

android {
    namespace = "io.github.jan.tennert.supabase.authkt.library"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "com.diajarkoding.imfit.sdkfenceproof.test"
    }
    sourceSets.getByName("main").kotlin.srcDirs("upstream/commonMain", "upstream/androidMain", "upstream/settingsMain")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/test/java")
    sourceSets.getByName("test").kotlin.srcDir("src/jvmTest/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.add("io.github.jan.supabase.annotations.SupabaseInternal")
        freeCompilerArgs.add("-Xmulti-platform")
        freeCompilerArgs.add("-Xcommon-sources=" + fileTree("upstream/commonMain") {
            include("**/*.kt")
        }.files.sortedBy { it.path }.joinToString(",") { it.absolutePath })
    }
}

dependencies {
    // Auth3.0.3 Android POM, not a replacement/upgrade of the Supabase BOM or application deps.
    api("io.github.jan-tennert.supabase:supabase-kt-android:3.0.3")
    api("androidx.startup:startup-runtime:1.2.0")
    api("androidx.browser:browser:1.8.0")
    api("com.squareup.okio:okio-jvm:3.9.1")
    api("com.russhwolf:multiplatform-settings-no-arg-android:1.3.0")
    api("com.russhwolf:multiplatform-settings-coroutines-android:1.3.0")
    implementation("org.kotlincrypto:secure-random-jvm:0.3.2")
    testImplementation(libs.junit)
    testImplementation("io.ktor:ktor-client-mock:3.0.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("io.ktor:ktor-client-mock:3.0.2")
}

tasks.withType<Test>().configureEach {
    doFirst { systemProperty("imfit.proof.runtimeClasspath", classpath.asPath) }
}
