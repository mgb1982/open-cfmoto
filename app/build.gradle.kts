plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.zanderp.opencfmoto"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Slim is the default ship shape: one ABI + R8. Opt out with -PslimApk=false (fat debug/CI).
    // -Pabi=armeabi-v7a ships the 32-bit ARM APK for phones whose Android is still 32-bit.
    val slimApk = (project.findProperty("slimApk") as String?)?.equals("false", ignoreCase = true) != true
    val abiFilter = (project.findProperty("abi") as String?)?.trim().orEmpty()

    defaultConfig {
        applicationId = "dev.zanderp.opencfmoto"
        minSdk = 29
        targetSdk = 36
        versionCode = 77
        // Fork builds: CI passes -PversionSuffix (e.g. "-zontes2" for a release, "-dev" otherwise) so
        // the About screen and the log's [BUILD] line tell which fork build is installed.
        // RideScreen AA: CI passes -PappVersion (from the release tag, e.g. "1", "1.1") and
        // -PversionSuffix ("" for a release, "-dev" otherwise). Shown as "v<versionName>".
        versionName = ((project.findProperty("appVersion") as String?) ?: "1") +
            ((project.findProperty("versionSuffix") as String?) ?: "-dev")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default OpenRouteService key used when the rider hasn't entered their own. Supply it via
        // `-PorsApiKey=...`, an `orsApiKey` in gradle.properties, or the ORS_API_KEY env var so the
        // key isn't hardcoded in source. Empty → routing falls back to the OSRM demo, then beeline.
        val orsDefaultKey = (project.findProperty("orsApiKey") as String?)
            ?: System.getenv("ORS_API_KEY")
            ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsDefaultKey\"")

        // Anonymous telemetry Worker base URL (no trailing slash). Empty disables uploads.
        // RideScreen AA builds get it from the TELEMETRY_URL repo variable (our own Worker in
        // telemetry/, see .github/workflows/telemetry.yml); local builds send nothing unless
        // -PtelemetryUrl=https://….workers.dev or the TELEMETRY_URL env var is given.
        val telemetryUrl = (project.findProperty("telemetryUrl") as String?)
            ?: System.getenv("TELEMETRY_URL")
            ?: ""
        buildConfigField("String", "TELEMETRY_URL", "\"$telemetryUrl\"")

        // Short git hash for Share Logs triage (configuration-cache safe).
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            workingDir(rootProject.projectDir)
            isIgnoreExitValue = true
        }.standardOutput.asText.map { text ->
            val t = text.trim()
            if (t.matches(Regex("[0-9a-f]{4,40}"))) t else "unknown"
        }.orElse("unknown")
        buildConfigField("String", "GIT_HASH", "\"${gitHash.get()}\"")

    }

    // Two distribution channels (see docs/PLAY-STORE.md):
    //  github — what the groups install from GitHub Releases: original applicationId, in-app
    //           update check, Ko-fi link. One ABI (APK size).
    //  play   — Google Play: own applicationId, no self-update (Play policy), donations through
    //           Google Play Billing (Play forbids external payment links), all ABIs (the AAB is split by Play).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
            buildConfigField("String", "STORE", "\"github\"")
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "EXTERNAL_DONATIONS", "true")
            if (slimApk || abiFilter.isNotEmpty()) {
                ndk {
                    abiFilters += listOf(abiFilter.ifEmpty { "arm64-v8a" })
                }
            }
        }
        create("play") {
            dimension = "store"
            applicationId = "io.github.mgb1982.ridescreen"
            buildConfigField("String", "STORE", "\"play\"")
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "EXTERNAL_DONATIONS", "false")
        }
    }

    signingConfigs {
        create("debugRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = slimApk
            isShrinkResources = slimApk
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debugRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    // Wireless Android Auto needs the packaged aa_privkey (same as prior releases).
    lint {
        disable += "PackagedPrivateKey"
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.mlkit.barcodescanner)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.jmdns)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.osmdroid)
    implementation(libs.maplibre)
    // Wear OS companion link (module :wear).
    implementation(libs.play.services.wearable)
    // Compile-time OkHttp for MapLibre cellular pin (MapLibre brings it as runtime only).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Donations in the Play build (Google Play Billing; Play requires Billing Library 8+).
    "playImplementation"("com.android.billingclient:billing:8.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}