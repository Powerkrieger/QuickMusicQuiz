plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.quickmusicquiz"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.quickmusicquiz"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // ─── Spotify App Remote SDK ──────────────────────────────────────────────────────
    // 1. Download the SDK zip from: https://github.com/spotify/android-sdk/releases
    // 2. Extract and copy spotify-app-remote-release-x.x.x.aar into app/libs/
    // 3. Update the filename below to match the version you downloaded
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    // Gson is required internally by the Spotify App Remote SDK
    implementation("com.google.code.gson:gson:2.10.1")

    // ─── ViewModel (Compose integration) ─────────────────────────────────────────────
    // lifecycle-viewmodel-compose provides viewModel() inside Composables
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ─── Coroutines ───────────────────────────────────────────────────────────────────
    // Adds Dispatchers.Main (Android's main/UI thread dispatcher)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ─── Standard AndroidX ────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
