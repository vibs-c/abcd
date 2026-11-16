// dj-analysis: Decoding (MediaExtractor/MediaCodec to mono PCM), the AudioAnalyzer interface and its native implementation, the pre-analysis service. Filled in stages 4 and 6.
// Android library. Kotlin is compiled by AGP's built-in Kotlin support, as in androidApp.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aiqulabs.abcd.dj.analysis"
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
