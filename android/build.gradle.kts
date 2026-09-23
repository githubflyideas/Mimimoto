plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "mimimoto.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ideas.githubfly.mimimoto"
        // Android 8.0. Covers the cheap tablets this product is aimed at, and
        // is high enough for java.time without desugaring — which :core needs.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-pilot"
    }

    buildTypes {
        // Debug builds are signed with the auto-generated debug keystore, so CI
        // produces an installable APK with no secrets configured. A release
        // build needs a real keystore; that is the user's to create.
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
}
