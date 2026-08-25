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

    // Slim is the default ship shape: arm64-only + R8. Opt out with -PslimApk=false (fat debug/CI).
    val slimApk = (project.findProperty("slimApk") as String?)?.equals("false", ignoreCase = true) != true

    defaultConfig {
        applicationId = "dev.zanderp.opencfmoto"
        minSdk = 29
        targetSdk = 36
        versionCode = 72
        versionName = "2.0.16-pre"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default OpenRouteService key used when the rider hasn't entered their own. Supply it via
        // `-PorsApiKey=...`, an `orsApiKey` in gradle.properties, or the ORS_API_KEY env var so the
        // key isn't hardcoded in source. Empty → routing falls back to the OSRM demo, then beeline.
        val orsDefaultKey = (project.findProperty("orsApiKey") as String?)
            ?: System.getenv("ORS_API_KEY")
            ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsDefaultKey\"")

        // Anonymous telemetry Worker base URL (no trailing slash). Empty disables uploads.
        // Override: -PtelemetryUrl=https://….workers.dev  or TELEMETRY_URL env / gradle.properties
        val telemetryUrl = (project.findProperty("telemetryUrl") as String?)
            ?: System.getenv("TELEMETRY_URL")
            ?: "https://opencfmoto-telemetry.hello-3d9.workers.dev"
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

        if (slimApk) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = slimApk
            isShrinkResources = slimApk
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // Compile-time OkHttp for MapLibre cellular pin (MapLibre brings it as runtime only).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}