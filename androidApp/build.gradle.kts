import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.foundation)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.work.runtime)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.sqldelight.android)
    implementation(libs.ktor.client.core)
    // Android's own SQLite, without a device (Robolectric): the driver the
    // app really uses, which the shared JVM tests cannot reach.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

android {
    namespace = "space.sovereigncast.hopp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "space.sovereigncast.hopp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }
    buildFeatures {
        buildConfig = true
    }

    // Two distributions (ADR 0007, ADR 0011): `play` may later carry the FCM
    // fallback for push; `foss` (F-Droid, Zapstore) never contains anything
    // proprietary.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // One debug key for every build, checked in: CI otherwise signs each APK
    // with a fresh throwaway key, and Android refuses to install it over the
    // previous one. Debug builds only — the release key never leaves the
    // maintainer (docs/signing.md).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Reproducible builds for F-Droid (ADR 0011): no build-time-dependent
    // metadata in the APK.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
