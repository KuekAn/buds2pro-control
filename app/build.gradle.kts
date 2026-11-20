plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kuek.budscontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kuek.budscontrol"
        minSdk = 28  // DynamicsProcessing (EQ)
        // Deliberately 30: a sideloaded personal app, and targeting 30 avoids the
        // Android 12 rule that blocks starting a foreground service from a
        // Bluetooth broadcast in the background (needed to auto-connect).
        targetSdk = 30
        versionCode = 4
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// No dependencies: framework APIs only. No INTERNET permission anywhere.

dependencies {
    // Unit tests only (EQ profile parser); nothing ships in the APK.
    testImplementation("junit:junit:4.13.2")
}
