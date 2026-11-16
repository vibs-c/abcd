import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// dj-data: the DJ's own Room database (abcd_dj.db), kept separate from Metrolist's song.db so its
// schema and migrations stay untouched (brief §6.1). Kotlin Multiplatform with an Android target only,
// the same way :shared is set up, so other platforms can be added later without moving code.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.ksp)
}

kotlin {
    android {
        namespace = "com.aiqulabs.abcd.dj.data"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 24
        withHostTest {}
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":dj-core"))
            api(libs.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}

// Room writes the exported schema of every version here; they are committed so migrations can be
// tested against them later.
class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDirectory: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDirectory.path}")
}

ksp {
    arg(RoomSchemaArgProvider(layout.projectDirectory.dir("schemas").asFile))
}
