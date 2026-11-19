plugins {
    alias(libs.plugins.org.jetbrains.kotlin.jvm)
}

dependencies {
    implementation(projects.core.architecture)
    implementation(libs.konsist)
    testImplementation(libs.junit.junit)
}

tasks.withType<Test>().configureEach {
    // 🛡️ Bootstrapping Guarantee: generate the actual canary analysis report before these tests.
    dependsOn(":core:canary-violations:lintDemoDebug")

    // Pass the absolute checkout root and variant-scoped canary XML report to policy tests.
    systemProperty("PROJECT_ROOT", project.rootDir.absolutePath)
    systemProperty("CANARY_LINT_XML_REPORT", "${project.rootDir}/core/canary-violations/build/reports/lint-results-demoDebug.xml")
}
