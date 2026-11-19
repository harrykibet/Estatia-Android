plugins {
    alias(libs.plugins.org.jetbrains.kotlin.jvm)
}

dependencies {
    implementation(projects.core.architecture)
    implementation(libs.konsist)
    testImplementation(libs.junit.junit)
}

tasks.withType<Test>().configureEach {
    // 🛡️ Bootstrapping Guarantee: The architecture tests MUST have access to the canary report.
    // Declare explicit dependency to ensure it runs before this module's tests.
    dependsOn(":core:canary-violations:copyDemoDebugLintReports")

    // Pass the absolute checkout root and structured canary XML report to policy tests.
    systemProperty("PROJECT_ROOT", project.rootDir.absolutePath)
    systemProperty("CANARY_LINT_XML_REPORT", "${project.rootDir}/core/canary-violations/build/reports/lint-results.xml")
}
