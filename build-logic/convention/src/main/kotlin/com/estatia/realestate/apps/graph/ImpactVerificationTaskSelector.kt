package com.estatia.realestate.apps.graph

/**
 * Selects verification tasks that are actually available for a module's Gradle plugin type.
 *
 * Android application/library variants can run the targeted DemoDebug lint and unit-test tasks.
 * Android instrumentation-test modules are built, but their tests are executed only by the
 * device-backed smoke-test workflow. JVM modules use the standard Gradle test task.
 */
internal object ImpactVerificationTaskSelector {
    private val androidApplicationPlugins = setOf(
        "com.android.application",
        "com.android.library"
    )
    private val jvmPlugins = setOf(
        "org.jetbrains.kotlin.jvm",
        "java",
        "java-library"
    )

    fun select(
        projectPath: String,
        pluginIds: Set<String>,
        registeredTaskNames: Set<String>
    ): List<String> {
        val candidateTasks = when {
            "com.android.test" in pluginIds -> listOf("assemble")
            pluginIds.any { it in androidApplicationPlugins } ->
                listOf("lintDemoDebug", "testDemoDebugUnitTest")
            pluginIds.any { it in jvmPlugins } -> listOf("test")
            else -> emptyList()
        }

        return candidateTasks
            .filter { it in registeredTaskNames }
            .map { "$projectPath:$it" }
    }
}
