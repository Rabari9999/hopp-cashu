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
    // The Nostr key into the password manager (device feedback 2026-10-16): the
    // system's own Credential Manager (Android 14+, any provider). Play services for
    // older Android only in the Play build, below — F-Droid's stays free of them.
    implementation(libs.androidx.credentials)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.foundation)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.glance.appwidget)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.work.runtime)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.sqldelight.android)
    implementation(libs.ktor.client.core)
    // Scanning a wallet's NWC QR code (device feedback 2026-10-14): CameraX for
    // the picture, ZXing for the code — both free, no Google services (foss).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // Android's own SQLite, without a device (Robolectric): the driver the
    // app really uses, which the shared JVM tests cannot reach.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Sync tests build entries and documents as the shared code does.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.client.mock)
    // secp256k1 for the host JVM: Robolectric runs on it, not on Android (Nostr identity tests).
    testImplementation(libs.secp256k1.jni.jvm)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.glance.appwidget.testing)
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
val appVersion = "0.3.2"
// A beta's versionCode: minutes since 2026 at its commit, so every beta handed on updates the one
// before (delta audit 2026-10-22). Its own app id, so it never meets the release's codes.
val betaVersionCode: Int? = (git("log", "-1", "--format=%ct")?.toLongOrNull()
    ?: System.getenv("CI_COMMIT_TIMESTAMP")?.let { OffsetDateTime.parse(it).toEpochSecond() })
    ?.let { ((it - 1_767_225_600L) / 60).coerceAtLeast(1).toInt() }
val betaKeystore: File? = System.getenv("HOPP_BETA_KEYSTORE")?.let(::File)?.takeIf { it.isFile && System.getenv("HOPP_BETA_KEYSTORE_PASSWORD") != null }
// The device check's emulators only (scripts/device-check.sh): a beta with the debug key, marked
// "-local" — never one to hand on (security audit 2026-10-22, H-04).
val betaForDeviceCheck = providers.gradleProperty("hopp.beta.deviceCheck").orNull == "true"


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
        versionCode = 7
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
        // A beta key of its own (security review 2026-10-10, M-07; audit 2026-10-22,
        // H-04): the debug key above is public, so anyone can sign an APK that
        // installs over a beta signed with it and reads its data. Betas are signed
        // with HOPP_BETA_KEYSTORE (a CI file variable, protected) and its password
        // only; without them no beta is built (docs/signing.md).
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
        // R8 (package 5): smaller and faster, and what the stores get. Configured
        // before "beta" is created, which copies it.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // An app of its own (2026-10-14): "space.sovereigncast.hopp.beta" installs
        // next to the release from Zapstore, F-Droid or GitLab, with its own data.
        // A beta can never update the release anyway — another key signs it.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            signingConfig = signingConfigs.findByName("beta") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            revision?.let { versionNameSuffix = "-beta.$it" + if (betaKeystore == null) "-local" else "" }
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

// The iOS app has the same version (device feedback 2026-10-15: it still said
// 0.0.1). Info.plist reads it from Version.xcconfig, kept in step here on every
// Android build; VersionTest fails if it is not.
val syncIosVersion by tasks.registering {
    val file = rootProject.file("iosApp/Configuration/Version.xcconfig")
    val text = "// Written by Gradle (androidApp/build.gradle.kts, syncIosVersion) — do not edit.\n" +
        "MARKETING_VERSION = $appVersion\nCURRENT_PROJECT_VERSION = ${android.defaultConfig.versionCode}\n"
    inputs.property("text", text)
    outputs.file(file)
    doLast { if (!file.isFile || file.readText() != text) file.writeText(text) }
}
tasks.named("preBuild") { dependsOn(syncIosVersion) }

// After the flavours exist: Google Password Manager through Play services on
// Android before 14, in the Play build only.
dependencies {
    "playImplementation"(libs.androidx.credentials.playServices)
}

androidComponents {
    onVariants(selector().withBuildType("beta")) { variant ->
        betaVersionCode?.let { code -> variant.outputs.forEach { it.versionCode.set(code) } }
    }
}

// No beta with the public debug key (security audit 2026-10-22, H-04): packaging one stops here,
// unless the device check asks for its own (-Phopp.beta.deviceCheck=true).
val noBetaKey = betaKeystore == null && !betaForDeviceCheck
tasks.configureEach {
    if (noBetaKey && name.startsWith("package") && name.endsWith("Beta")) doFirst {
        throw GradleException("A beta is signed with the beta key only: set HOPP_BETA_KEYSTORE and HOPP_BETA_KEYSTORE_PASSWORD (docs/signing.md).")
    }
}
