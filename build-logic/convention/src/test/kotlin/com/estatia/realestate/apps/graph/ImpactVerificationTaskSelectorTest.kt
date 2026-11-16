package com.estatia.realestate.apps.graph

import org.junit.Assert.assertEquals
import org.junit.Test

class ImpactVerificationTaskSelectorTest {

    @Test
    fun selectsOnlyRegisteredDemoDebugTasksForAndroidApplications() {
        assertEquals(
            listOf(":app:lintDemoDebug", ":app:testDemoDebugUnitTest"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":app",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = setOf(
                    "assemble",
                    "lintDemoDebug",
                    "testDemoDebugUnitTest",
                    "test"
                )
            )
        )
    }

    @Test
    fun selectsAndroidLibraryVariantTasks() {
        assertEquals(
            listOf(":core:analytics:lintDemoDebug", ":core:analytics:testDemoDebugUnitTest"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":core:analytics",
                pluginIds = setOf("com.android.library", "org.jetbrains.kotlin.android"),
                registeredTaskNames = setOf("lintDemoDebug", "testDemoDebugUnitTest")
            )
        )
    }

    @Test
    fun buildsAndroidInstrumentationTestModulesWithoutInventingVariantLintTasks() {
        assertEquals(
            listOf(":benchmark:assemble"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":benchmark",
                pluginIds = setOf("com.android.test"),
                registeredTaskNames = setOf(
                    "assemble",
                    "lint",
                    "testDemoDebugUnitTest"
                )
            )
        )
    }

    @Test
    fun selectsTheStandardTestTaskForKotlinJvmModules() {
        assertEquals(
            listOf(":lint:test"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":lint",
                pluginIds = setOf("org.jetbrains.kotlin.jvm"),
                registeredTaskNames = setOf("test", "jar", "assemble")
            )
        )
    }

    @Test
    fun excludesIntermediateProjectsWithoutVerificationPlugins() {
        assertEquals(
            emptyList<String>(),
            ImpactVerificationTaskSelector.select(
                projectPath = ":core",
                pluginIds = emptySet(),
                registeredTaskNames = setOf("help", "tasks")
            )
        )
    }

    @Test
    fun neverEmitsTasksThatAreNotRegistered() {
        assertEquals(
            emptyList<String>(),
            ImpactVerificationTaskSelector.select(
                projectPath = ":benchmark",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = setOf("lintProdDebug", "testProdDebugUnitTest")
            )
        )
    }
}
