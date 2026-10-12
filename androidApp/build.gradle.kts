import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

// Which state of the code an APK is: the commit's date (UTC) and short hash —
// not the build time, so two builds of one commit stay identical (ADR 0011).
// Falls back to GitLab's variables where git refuses the checkout (another
// user owns it inside the CI container), and to nothing outside a repository.
fun git(vararg args: String): String? = providers.exec {
    commandLine("git", *args)
    environment("TZ", "UTC")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }

val onReleaseTag = git("describe", "--exact-match", "--tags", "HEAD") != null || System.getenv("CI_COMMIT_TAG") != null
val revision: String? = git("log", "-1", "--format=%cd-%h", "--date=format-local:%Y%m%d-%H%M")
    ?: System.getenv("CI_COMMIT_SHORT_SHA")?.let { sha ->
        val stamp = System.getenv("CI_COMMIT_TIMESTAMP")?.let { ts ->
            OffsetDateTime.parse(ts).atZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        }
        listOfNotNull(stamp, sha).joinToString("-")
    }
val appVersion = "0.0.1"
val betaKeystore: File? = System.getenv("HOPP_BETA_KEYSTORE")?.let(::File)?.takeIf { it.isFile && System.getenv("HOPP_BETA_KEYSTORE_PASSWORD") != null }

// hopp-0.0.1-20261008-1012-fd61d90-foss-debug.apk; on a release tag the
// version alone says it: hopp-0.1.0-foss-release-unsigned.apk.
base {
    archivesName.set(listOfNotNull("hopp", appVersion, revision.takeUnless { onReleaseTag }).joinToString("-"))
}

android {
    namespace = "space.sovereigncast.hopp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "space.sovereigncast.hopp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = appVersion
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
        // A beta key of its own (security review 2026-10-10, M-07): the debug key
        // above is public, so anyone can sign an APK that installs over a beta
        // signed with it. Given HOPP_BETA_KEYSTORE (a CI file variable, protected)
        // and its password, betas are signed with that key; without, with the
        // debug key as before (docs/signing.md).
        betaKeystore?.let { store ->
            create("beta") {
                storeFile = store
                storePassword = System.getenv("HOPP_BETA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOPP_BETA_KEY_ALIAS") ?: "hopp-beta"
                keyPassword = System.getenv("HOPP_BETA_KEY_PASSWORD") ?: System.getenv("HOPP_BETA_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // Settings shows it, so a bug report names the exact build.
            revision?.let { versionNameSuffix = "-dev.$it" }
        }
        // Release code, debug key: what testers install to judge how Hopp
        // feels. A debug build is not debuggable for nothing — interpreted,
        // with the runtime's checks on, Compose in it runs several times
        // slower and its animations stutter (device feedback 2026-10-08).
        // Signed like the debug builds, so it installs over them.
        // R8 (package 5): smaller and faster, and what the stores get. Configured
        // before "beta" is created, which copies it.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("beta") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            revision?.let { versionNameSuffix = "-beta.$it" }
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
