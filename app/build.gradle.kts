plugins {
    // AGP 9 ships built-in Kotlin support; no kotlin.android plugin.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// FCM needs a Firebase project's google-services.json. Apply the plugin only
// when it's present so the app still builds (push inactive) without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.riiiiiiiley.discourse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riiiiiiiley.discourse"
        minSdk = 29
        targetSdk = 36
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

    buildFeatures {
        compose = true
        // Exposes VERSION_NAME/VERSION_CODE to the in-app updater.
        buildConfig = true
    }
}

dependencies {
    // The same Matrix Rust SDK the iOS app builds on, via its Kotlin bindings.
    implementation("org.matrix.rustcomponents:sdk-android:26.06.18")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // ProcessLifecycleOwner: Platform.isAppActive (room-list unread clearing).
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    // EXIF orientation for outgoing-image sanitizing (MediaProcessing).
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.browser:browser:1.9.0")
    // FCM push. Pusher is registered against sygnal's gcm pushkin.
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-messaging")
    // Full-screen video attachment player (VideoPlayerDialog).
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    // WebViewCompat.addDocumentStartJavaScript for the Element Call bridge.
    implementation("androidx.webkit:webkit:1.14.0")
}
