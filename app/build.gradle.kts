import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "de.powerizzle.quickmusicquiz"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "de.powerizzle.quickmusicquiz"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Read from environment variables — set locally or via GitHub Secrets in CI.
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile     = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig   = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    defaultConfig {
        // CI (see .github/workflows/build.yml) sets this via the SPOTIFY_CLIENT_ID repo secret;
        // local Android Studio builds fall back to local.properties. Same setup as AmelieMusikster.
        val spotifyClientId = System.getenv("SPOTIFY_CLIENT_ID")
            ?: localProperties.getProperty("SPOTIFY_CLIENT_ID", "")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }
}

dependencies {
    // ─── Spotify App Remote SDK ──────────────────────────────────────────────────────
    // 1. Download the SDK zip from: https://github.com/spotify/android-sdk/releases
    // 2. Extract and copy spotify-app-remote-release-x.x.x.aar into app/libs/
    // 3. Update the filename below to match the version you downloaded
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    // Gson is required internally by the Spotify App Remote SDK
    implementation(libs.gson)

    // ─── ViewModel (Compose integration) ─────────────────────────────────────────────
    // lifecycle-viewmodel-compose provides viewModel() inside Composables
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ─── Coroutines ───────────────────────────────────────────────────────────────────
    // Adds Dispatchers.Main (Android's main/UI thread dispatcher)
    implementation(libs.kotlinx.coroutines.android)

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
