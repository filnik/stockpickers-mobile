plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Root-only, like Spotless and for the same reason: detekt's per-module task
// defaults `source` to src/main/kotlin, which in a KMP module means it analyses
// NOTHING. Pointing one root task at the source directories sidesteps the whole
// source-set-detection problem.
//
// Type resolution is deliberately off. It does not cover commonMain — i.e. this
// project's entire codebase (detekt#5961, still open) — and in a KMP build it
// compiles every Android variant to get a classpath, which is minutes of work for
// analysis it cannot then perform. Rules needing it are excluded from the config.
detekt {
    buildUponDefaultConfig = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    source.setFrom(
        "shared/src",
        "composeApp/src",
    )
    parallel = true
}

// Applied only here, on purpose. Spotless resolves targets from file globs rather
// than from each module's Kotlin source sets, so one root block covers :shared
// (KMP: commonMain/androidMain/iosMain/commonTest/androidHostTest) and :composeApp
// without a convention-plugin layer this project deliberately does not have.
spotless {
    kotlin {
        target("**/*.kt")
        // Everything under build/ is generated: Room's KSP output, the Compose
        // Resources accessors, and SupabaseConfig.kt (materialised from
        // local.properties). Formatting generated sources fights the generator.
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
            .customRuleSets(listOf(libs.ktlint.composeRules.get().toString()))
            // Rule toggles live here rather than in .editorconfig: Spotless does not
            // reliably resolve ktlint_* properties out of the file's glob section,
            // and these are ktlint-specific anyway. .editorconfig stays the source of
            // truth for what the IDE and every other tool needs (indent, charset,
            // the 120-column guide).
            .editorConfigOverride(
                mapOf(
                    // Does not understand KMP: it wants DatabaseBuilder.kt renamed after
                    // its single class, which breaks the expect/actual .android.kt /
                    // .ios.kt pairing, and NavKeys.kt renamed to AppNavKey.kt, which
                    // contradicts the layout CLAUDE.md documents.
                    "ktlint_standard_filename" to "disabled",
                    // Composables are PascalCase; test methods use the project's
                    // WHEN_condition_THEN_expectation naming. Both are deliberate.
                    "ktlint_standard_function-naming" to "disabled",
                    // Compose UI constants are top-level PascalCase vals by convention
                    // (PositiveGreen, ChartHeight, RankWidth).
                    "ktlint_standard_property-naming" to "disabled",
                    // Wants present-tense lambda names (onSortSelect), but onXxxSelected
                    // is the prevailing Compose convention and is used consistently in
                    // both screens. Churn for a contested style point.
                    "ktlint_compose_parameter-naming" to "disabled",
                    // Allowlisted, NOT disabled: LocalMonoFamily is a deliberate,
                    // documented CompositionLocal, so the rule stays active and any NEW
                    // one still has to be justified.
                    "compose_allowed_composition_locals" to "LocalMonoFamily",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}
