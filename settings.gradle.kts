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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mimimoto"

// `:testkit` is the in-repo test runner, depended on only as `testImplementation`.
// It is a module of its own so that every module can test its own `internal`
// declarations: Kotlin gives a test source set friend access to its own module
// and no other, so a shared harness cannot live inside one of them.
include(":core", ":server", ":testkit")

// The Android app joins the build only where an SDK exists, so the server side
// still builds — and its tests still run — on a machine or CI job with no
// Android SDK installed. `scripts/build.sh` bypasses Gradle entirely for hosts
// that cannot reach Maven at all.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":android")
}
