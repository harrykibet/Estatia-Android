import com.jraska.module.graph.assertion.GraphRulesExtension
import java.io.File

buildscript {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.com.android.application)  apply false
    alias(libs.plugins.com.android.library) apply false
    alias(libs.plugins.org.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.com.google.devtools.ksp) apply false
    alias(libs.plugins.com.google.gms.google.services) apply false
    alias(libs.plugins.com.google.firebase.crashlytics) apply false
    alias(libs.plugins.com.google.firebase.perf) apply false
    alias(libs.plugins.androidx.navigation.safeargs.kotlin) apply false
    alias(libs.plugins.com.google.dagger.hilt.android) apply false
    alias(libs.plugins.androidx.room)  apply false
    alias(libs.plugins.org.jetbrains.dokka) apply false
    alias(libs.plugins.com.google.android.libraries.mapsplatform.secrets.gradle.plugin) apply false
    alias(libs.plugins.org.sonarqube) apply false
    alias(libs.plugins.org.owasp.dependencycheck)
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.module.graph)
    alias(libs.plugins.kotlin.serialization)  apply false
    id("com.estatia.realestate.apps.architecture")
}

// Scan resolved Gradle configurations across the complete multi-project build.
// Central Analyzer is redundant for Gradle-resolved artifacts and can introduce
// flaky Maven Central lookups; NVD/KEV analysis remains enabled.
extensions.configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension>("dependencyCheck") {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    outputDirectory = layout.buildDirectory.dir("reports/dependency-check").get().asFile
    skipTestGroups = true
    failOnError = true
    // Resolve only published application/library compile and runtime classpaths.
    // AGP 9's private KSP/lint model configurations must not be force-resolved
    // by an external analysis task (Gradle rejects that outside their exclusive lock).
    scanConfigurations = listOf(
        "compileClasspath",
        "runtimeClasspath",
        "demoDebugCompileClasspath",
        "demoDebugRuntimeClasspath",
        "demoReleaseCompileClasspath",
        "demoReleaseRuntimeClasspath",
        "demoNonMinifiedReleaseCompileClasspath",
        "demoNonMinifiedReleaseRuntimeClasspath",
        "demoBenchmarkCompileClasspath",
        "demoBenchmarkRuntimeClasspath",
        "demoBenchmarkReleaseCompileClasspath",
        "demoBenchmarkReleaseRuntimeClasspath",
        "prodDebugCompileClasspath",
        "prodDebugRuntimeClasspath",
        "prodReleaseCompileClasspath",
        "prodReleaseRuntimeClasspath",
        "prodNonMinifiedReleaseCompileClasspath",
        "prodNonMinifiedReleaseRuntimeClasspath",
        "prodBenchmarkCompileClasspath",
        "prodBenchmarkRuntimeClasspath",
        "prodBenchmarkReleaseCompileClasspath",
        "prodBenchmarkReleaseRuntimeClasspath"
    )
    analyzers.centralEnabled = false
    // Back off between NVD API requests to reduce 429/503 responses during full refreshes.
    nvd.delay = 6000
    nvd.maxRetryCount = 20
    nvd.validForHours = 24
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { nvd.apiKey = it }
}

extensions.configure<GraphRulesExtension>("moduleGraphAssert") {
    maxHeight = 10
    configurations = setOf("api", "implementation")

    restricted = arrayOf(
        ":core:(?!canary-violations).* -X> :feature.*",
        ":feature.* -X> :feature:(?!shared-ui).*",
        ":core:domain -X> :core:(network|database|datastore|intelligence|notifications|security)",
        ":core:model -X> :core:(?!common).*"
    )
}

tasks.register("checkDependencyDrift") {
    description = "Checks for hardcoded dependencies."
    group = "verification"
    doLast {
        val rootDir = project.projectDir
        val magicStringRegex = Regex("""(implementation|api|testImplementation|debugImplementation|androidTestImplementation)\s*\(?\s*["']([^:"]+:[^:"]+:[^:"]+)["']""")
        val violations = mutableListOf<String>()
        rootDir.walkTopDown().forEach { file ->
            if (file.name == "build.gradle.kts" && !file.path.contains(".gradle") && !file.path.contains("build-logic")) {
                val content = file.readText()
                magicStringRegex.findAll(content).forEach { violations.add("${file.relativeTo(rootDir).path}: ${it.value}") }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("LAW-037: Dependency Drift detected. Hardcoded dependencies found:\n" + violations.joinToString("\n"))
        }
    }
}

// Sub-tasks in individual modules wire up to this global gate
tasks.register("auditReleaseSymbols") {
    description = "Global gate for release symbol obfuscation integrity (LAW-038)."
    group = "verification"
}

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Executes all architectural and policy enforcement gates."

    // 1. Standard Implementation Guard (Lint)
    dependsOn(":lint:assemble") // Ensure custom lint rules are built
    dependsOn(":lint:test")     // Run detector unit tests and DocParityTest

    // 2. Global Structural Enforcement (Konsist & Regression Tests)
    dependsOn(":core:testing-architecture:test")
    dependsOn(":core:canary-violations:lintDemoDebug")
    dependsOn("assertModuleGraph")
    dependsOn("generateModuleGraphs")
    dependsOn(":core:data:compileDemoDebugKotlin")
    dependsOn(":core:network:compileDemoDebugKotlin")
    dependsOn("checkDependencyDrift")
    dependsOn("auditReleaseSymbols")
}
