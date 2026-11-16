// dj-ui: Compose screens: Setup, Preparing, Live, Chorus editor, Lab. Filled from stage 3.
// Android library. Kotlin is compiled by AGP's built-in Kotlin support, as in androidApp.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aiqulabs.abcd.dj.ui"
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
}
