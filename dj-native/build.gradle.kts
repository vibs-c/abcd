// dj-native: the C++ audio analysis (Essentia + our own structure/chorus code) behind one JNI function.
// The portable core lives in src/main/cpp/core; only src/main/cpp/jni and this module are Android-specific.
// Essentia (AGPL-3.0) and Eigen (MPL-2.0) are git submodules in third_party/.
plugins {
    alias(libs.plugins.android.library)
}

// Same CMake as :opusDecoder (installed through the Android SDK manager).
val djCmakeVersion = "4.1.2"

android {
    namespace = "com.aiqulabs.abcd.dj.nativelib"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    ndkVersion = libs.versions.androidNdk.get()

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // arm64 for current phones, armeabi-v7a for older 32-bit phones, x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = djCmakeVersion
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
