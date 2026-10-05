plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.termdesk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.termdesk.app"
        minSdk = 26
        // targetSdk 28, on purpose and with a cost: Android 10+ forbids an app
        // process (untrusted_app) from EXECUTING a file in its own data dir, so
        // a Termux userland unpacked into files/ cannot run at all - measured
        // here: "error=13, Permission denied" on files/usr/bin/bash. Apps that
        // target 28 or lower land in a domain where exec is allowed, which is
        // exactly why Termux itself ships with targetSdkVersion 28.
        //
        // What it costs: legacy (non-scoped) storage behaviour, no Play Store
        // upload without raising it again, and no API-30+ package visibility
        // rules. TermDesk is sideloaded, uses only its own app-specific dirs,
        // and its whole point is running the payload - so the trade is taken
        // knowingly. Set TERMDESK_MODERN_TARGET_SDK=1 to build at 35 instead,
        // which gives up the local kernel and keeps everything else.
        targetSdk = if (System.getenv("TERMDESK_MODERN_TARGET_SDK") == "1") 35 else 28
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // WebSocket client used to talk to the TermDesk PC agent.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
