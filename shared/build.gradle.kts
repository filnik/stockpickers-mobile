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
    alias(libs.plugins.skie)
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
        // AGP 9 KMP library: JVM unit tests are OPT-IN. This creates the `hostTest`
        // compilation + the `androidHostTest` source set (the plugin's name for what
        // classic KMP calls `androidUnitTest`), and its `testAndroidHostTest` task.
        // `includeAndroidResources` lets Robolectric/Roborazzi read the Compose
        // resources the screens render; `returnDefaultValues` stubs the android.jar.
        withHostTest {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
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
            // Without an explicit bundle ID the linker warns:
            // "Cannot infer a bundle ID from packages of source files ...".
            binaryOption("bundleId", "app.stockpickers.kmp.shared")
        }
    }

    // `expect class DatabaseBuilderFactory` / `expect val platformModule` are
    // Beta and warn on every compile. The API is stable in practice for KMP.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
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
                implementation(libs.lifecycle.viewmodel.navigation3)

                implementation(libs.navigation3.ui)

                // Vico charts. Used by the Android detail screen (TickerDetailScreen);
                // iOS renders its own Swift Charts and never invokes this composable,
                // but the dependency is klib-compatible so it compiles for every target.
                implementation(libs.vico.multiplatform)
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
        // Shared, framework-free tests: run on JVM (androidHostTest) AND iOS
        // (iosSimulatorArm64Test). kotlin.test + Turbine + hand-written fakes only —
        // no MockK (JVM-only), no Room actual (needs a platform SQLite driver).
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.turbine)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        // JVM-only test that needs a real SQLite engine for the DAO SQL.
        // `sqlite-bundled-jvm` supplies the desktop native the Android AAR lacks, so
        // `BundledSQLiteDriver` loads on the host JVM. (Roborazzi snapshot tests live
        // in :composeApp — a classic app module where composeResources can be staged
        // onto the test classpath; see that module's ScreenSnapshotTest.)
        getByName("androidHostTest").dependencies {
            implementation(libs.sqlite.bundled.jvm)
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }
    }
}

// Compose Multiplatform string/image resources. A stable, explicit package for the
// generated `Res` accessor (default would derive one from the module group) so the
// import path is predictable from Kotlin: `app.stockpickers.kmp.resources.Res`.
// `generateResClass = always` forces generation even though this is a library module.
compose.resources {
    publicResClass = true
    packageOfResClass = "app.stockpickers.kmp.resources"
    generateResClass = always
}

// SKIE turns the raw Obj-C export into idiomatic Swift: StateFlow -> AsyncSequence,
// suspend -> async/await, sealed -> enum. Enabled here (Kotlin is pinned to 2.4.0,
// which SKIE 0.10.13 supports). Because of it, iOS observes the shared ViewModel's
// StateFlow natively (see iosApp/.../TickerDetailView.swift) with no hand-rolled
// bridge. If Kotlin is ever bumped past what SKIE supports, either wait for a SKIE
// release or set `isEnabled = false` and re-introduce a manual Flow collector.
skie {
    isEnabled = true
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
