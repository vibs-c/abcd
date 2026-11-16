// dj-engine: DjMixEngine: two decks, AudioProcessors, sync loop, gain matching. Design in docs/DJ_ENGINE_DESIGN.md (stage 7) must be approved before code (stage 8).
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
    implementation(project(":dj-core"))
    implementation(project(":dj-native"))
}
