plugins {
    alias(libs.plugins.android.application)
}

// Wear OS companion for OpenCfMoto. It MUST keep the phone app's applicationId and signing key:
// the Wearable Data Layer only delivers messages between apps that share both.
android {
    namespace = "dev.zanderp.opencfmoto.wear"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.zanderp.opencfmoto"
        minSdk = 30          // Wear OS 3 (Galaxy Watch 4 and newer)
        targetSdk = 36
        versionCode = 1
        versionName = "1.0" + ((project.findProperty("versionSuffix") as String?) ?: "")
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.play.services.wearable)
}
