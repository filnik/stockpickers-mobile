plugins {
    // NOTE: no `org.jetbrains.kotlin.android` here — AGP 9.0+ ships built-in
    // Kotlin support and rejects the standalone plugin.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Roborazzi snapshot tests live HERE, not in :shared. This is a classic
    // com.android.application module, so the CMP `composeResources` are merged into
    // the app's assets and Robolectric can read them — which they are NOT on the
    // AGP9 KMP `androidHostTest` classpath (see :shared ScreenSnapshotTest history).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.stockpickers.kmp.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.stockpickers.kmp.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources/assets — this is what
            // makes the CMP composeResources (.cvr) reachable during snapshot capture.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.shared)
    implementation(compose.runtime)
    implementation(compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)

    // Roborazzi screenshot tests (JVM/Robolectric) of the shared CMP screens.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(compose.material3)
    testImplementation(compose.components.resources)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

// Stage :shared's compiled Compose resources (.cvr) onto the unit-test classpath in
// the package-qualified layout the runtime expects
// (composeResources/app.stockpickers.kmp.resources/...). The AGP9 KMP androidLibrary
// does NOT propagate composeResources to a consumer's host-test classpath, so CMP's
// classpath reader (PreviewContextConfigurationEffect) otherwise throws
// MissingResourceException in the Roborazzi snapshot tests.
val stageComposeResForTest = tasks.register<Copy>("stageComposeResForTest") {
    val shared = project(":shared")
    dependsOn(shared.tasks.named("prepareComposeResourcesTaskForCommonMain"))
    from(shared.layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("composeResForTest/composeResources/app.stockpickers.kmp.resources"))
}

android.sourceSets.getByName("test").resources.srcDir(
    layout.buildDirectory.dir("composeResForTest").get().asFile,
)

tasks.matching {
    it.name == "processDebugUnitTestJavaRes" || it.name == "testDebugUnitTest"
}.configureEach { dependsOn(stageComposeResForTest) }
