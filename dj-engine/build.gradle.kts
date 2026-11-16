// dj-engine: DjMixEngine: two decks (ExoPlayer + our audio chain), audio focus, transitions, and the
// media-session player shown while DJ Mode is on. Design: docs/DJ_ENGINE_DESIGN.md (approved stage 7).
// Android library. Kotlin is compiled by AGP's built-in Kotlin support, as in androidApp.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aiqulabs.abcd.dj.engine"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":dj-core"))
    implementation(project(":dj-native"))
    api(libs.media3.exoplayer)
    api(libs.media3.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit4)
}
