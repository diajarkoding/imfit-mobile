import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.diajarkoding.imfit"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.diajarkoding.imfit"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "dataSource"
    productFlavors {
        create("demo") {
            dimension = "dataSource"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
        create("production") {
            dimension = "dataSource"
        }
    }

    buildTypes {
        debug {
            // Supabase config from local.properties
            val supabaseUrl = localProperties.getProperty("SUPABASE_URL", "")
                .trim().removeSurrounding("\"")
            val supabaseKey = localProperties.getProperty("SUPABASE_ANON_KEY", "")
                .trim().removeSurrounding("\"")
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Supabase config from local.properties (or override for production)
            val supabaseUrl = localProperties.getProperty("SUPABASE_URL", "")
                .trim().removeSurrounding("\"")
            val supabaseKey = localProperties.getProperty("SUPABASE_ANON_KEY", "")
                .trim().removeSurrounding("\"")
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // --- CORE LIBRARY DESUGARING ---
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- CORE & UI ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.splashscreen)

    // --- STATE MANAGEMENT & LIFECYCLE ---
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // --- NAVIGATION ---
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // --- DEPENDENCY INJECTION (HILT) ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.hilt.compiler)

    // --- IMAGE LOADING ---
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // --- LOCAL STORAGE ---
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)

    // --- SUPABASE ---
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)

    // --- KTOR (for Supabase) ---
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.utils)

    // --- SERIALIZATION ---
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    // --- TESTING ---
    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
