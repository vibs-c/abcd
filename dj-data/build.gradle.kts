// dj-data: The separate Room database abcd_dj.db, its DAOs and repositories. Metrolist's MusicDatabase is never touched. Filled in stage 5.
// Android library. Kotlin is compiled by AGP's built-in Kotlin support, as in androidApp.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aiqulabs.abcd.dj.data"
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
