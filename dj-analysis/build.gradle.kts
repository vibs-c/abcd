// dj-analysis: decoding to mono PCM (in-process libopus for WebM/Opus, MediaExtractor + MediaCodec for
// everything else) and the native AudioAnalyzer. The analyzer interfaces and result models are in dj-core.
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
    api(project(":dj-core"))
    implementation(project(":dj-native"))
    // Media3's libopus decoder (vendored in :opusDecoder, already in the app) and its Matroska extractor:
    // decode WebM/Opus in-process instead of one platform-codec round trip per 20 ms frame.
    implementation(project(":opusDecoder"))
    implementation(libs.media3.extractor)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
