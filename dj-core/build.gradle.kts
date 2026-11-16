import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// dj-core: pure Kotlin DJ logic (models, DJ brain, scoring, technique selection).
// No Android imports. Kotlin Multiplatform with a JVM target today so Android (and later desktop)
// can consume it; add iOS targets here when cross-platform work starts.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Same toolchain as the rest of the build (shared, androidApp).
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
