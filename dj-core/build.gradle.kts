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

// dj-core must stay platform-free (owner rule: no Android imports). With only a JVM target the Kotlin
// compiler would accept android.*/java.* in commonMain, so this check enforces it on every build until
// iOS/desktop targets are added and the compiler does it for us.
val verifyPlatformFreeCommonMain by tasks.registering {
    val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val platformImport = """^\s*import\s+(android|androidx|java|javax|kotlin\.jvm)\."""
        val qualifiedUse = """(^|[^.\w])(android|java|javax)\.[a-z]+\.[A-Z]"""
        val forbidden = Regex("$platformImport|$qualifiedUse", RegexOption.MULTILINE)
        val offenders = sources.files.filter { forbidden.containsMatchIn(it.readText()) }.map { it.name }
        check(offenders.isEmpty()) { "dj-core commonMain must not use Android or JVM-only APIs: $offenders" }
    }
}
tasks.matching { it.name == "compileKotlinJvm" }.configureEach { dependsOn(verifyPlatformFreeCommonMain) }
