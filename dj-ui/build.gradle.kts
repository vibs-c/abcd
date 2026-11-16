// dj-ui: Compose screens: Setup, Preparing, Live, Chorus editor, Lab. Filled from stage 3.
// Android library using Metrolist's theme, strings (`Res`) and icons from :shared. Screens here are
// stateless: they take state and callbacks, and the host glue in androidApp wires them to playback.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":dj-core"))
    implementation(project(":shared"))
}
