plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.seum.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.seum.app"
        minSdk = 28
        // Pleos Connect Emulator 2.1.0 is API 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    useLibrary("android.car")

    signingConfigs {
        create("release") {
            // Release key lives outside the repo; fall back to the debug key so `assembleRelease` always works.
            val ks = System.getenv("SEUM_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("SEUM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SEUM_KEY_ALIAS") ?: "seum"
                keyPassword = System.getenv("SEUM_KEY_PASSWORD") ?: System.getenv("SEUM_KEYSTORE_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // seum.db.gz is already compressed; store it as-is so it can be streamed out on first launch.
        noCompress += "gz"
    }
}

dependencies {
    implementation(project(":engine"))

    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
