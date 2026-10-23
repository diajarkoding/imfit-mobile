pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "IMFIT"
include(":app")

// B1.1 proof artifact is opt-in and is NOT substituted into the normal application's SDK.
if (providers.gradleProperty("sdkFenceProof").orNull == "true") {
    include(":auth-fence-proof")
    project(":auth-fence-proof").projectDir = file("sdk/auth-fence-proof")
}
