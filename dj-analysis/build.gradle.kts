// dj-analysis: decoding (MediaExtractor + MediaCodec to mono PCM), the native AudioAnalyzer, and later
// the pre-analysis service (stage 6). The analyzer interfaces and result models are in dj-core.
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
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
