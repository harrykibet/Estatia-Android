package com.estatia.realestate.apps.graph

/**
 * Selects verification tasks that are actually available for a module's Gradle plugin type.
 *
 * Android application/library modules run lint and unit tests for the flavor affected by
 * changed source-set paths. Shared sources deliberately validate both DemoDebug and
 * ProdDebug. Android instrumentation-test modules are assembled here and executed only by
 * the device-backed smoke-test workflow. JVM modules use the standard Gradle test task.
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
    private val debugVariants = listOf("DemoDebug", "ProdDebug")

    fun select(
        projectPath: String,
        pluginIds: Set<String>,
        registeredTaskNames: Set<String>,
        changedFiles: List<String> = emptyList(),
        compileOnly: Boolean = false
    ): List<String> {
        val candidateTasks = when {
            "com.android.test" in pluginIds -> listOf("assemble")
            pluginIds.any { it in androidApplicationPlugins } -> {
                val variants = debugVariantsForChangedFiles(changedFiles)
                if (compileOnly) {
                    variants.map { "assemble$it" }
                } else {
                    variants.flatMap { variant ->
                        listOf("lint$variant", "test${variant}UnitTest")
                    }
                }
            }
            pluginIds.any { it in jvmPlugins } ->
                if (compileOnly) listOf("classes") else listOf("test")
            else -> emptyList()
        }

        return candidateTasks
            .filter { it in registeredTaskNames }
            .map { "$projectPath:$it" }
    }

    /**
     * Flavor source sets map to the corresponding debug variant. Any shared, build-logic,
     * or otherwise unclassified path is conservative and selects both flavors.
     */
    private fun debugVariantsForChangedFiles(changedFiles: List<String>): List<String> {
        if (changedFiles.isEmpty()) return debugVariants

        val selectedVariants = mutableSetOf<String>()
        changedFiles.forEach { changedPath ->
            val segments = changedPath.replace('\\', '/').split('/')
            val sourceSetIndex = segments.indexOf("src")
            val sourceSet = segments.getOrNull(sourceSetIndex + 1)

            when (sourceSet) {
                "demo", "testDemo", "androidTestDemo" -> selectedVariants += "DemoDebug"
                "prod", "testProd", "androidTestProd" -> selectedVariants += "ProdDebug"
                else -> selectedVariants += debugVariants
            }
        }

        return debugVariants.filter { it in selectedVariants }
    }
}
