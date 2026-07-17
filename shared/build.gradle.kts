import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // AGP 9: KMP modules use `com.android.kotlin.multiplatform.library`; the classic
    // `com.android.library` plugin is no longer compatible with the KMP plugin.
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// --- Secrets: read from local.properties (git-ignored) and generate a Kotlin source ---
// The anon key must never live in a tracked file. `local.properties` is in .gitignore;
// this task materialises it into build/ (also ignored) so commonMain can read it.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl: String = localProps.getProperty("SUPABASE_URL").orEmpty()
val supabaseAnonKey: String = localProps.getProperty("SUPABASE_ANON_KEY").orEmpty()

val generateSupabaseConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/supabase/kotlin")
    val url = supabaseUrl
    val key = supabaseAnonKey
    inputs.property("supabaseUrl", url)
    inputs.property("supabaseAnonKey", key)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("app/stockpickers/kmp")
        pkgDir.mkdirs()
        pkgDir.resolve("SupabaseConfig.kt").writeText(
            """
            |package app.stockpickers.kmp
            |
            |// GENERATED from local.properties — do not edit, do not commit.
            |internal object SupabaseConfig {
            |    const val URL: String = "$url"
            |    const val ANON_KEY: String = "$key"
            |    val isConfigured: Boolean get() = URL.isNotEmpty() && ANON_KEY.isNotEmpty()
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    // AGP 9 KMP DSL: the Android target is declared here, not in a separate
    // top-level `android { }` block.
    androidLibrary {
        namespace = "app.stockpickers.kmp"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }
    // NOTE: iosX64 (the Intel simulator) is intentionally ABSENT.
    // Compose Multiplatform 1.11.1 publishes no iosX64 artifacts — the last
    // release that did was 1.11.0-alpha01 — so declaring the target breaks
    // dependency resolution for commonMain. Apple Silicon uses iosSimulatorArm64.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSupabaseConfig)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)

                implementation(libs.koin.core)
                implementation(libs.koin.compose.viewmodel)

                implementation(libs.lifecycle.viewmodel)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.koin.android)
            }
        }
        iosMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

// Room 3.0 registers its extension as `room3` (not `room` as in Room 2.x).
room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room compiler must run for every KMP target that uses the database.
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
