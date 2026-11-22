package com.estatia.realestate.apps.graph

import org.junit.Assert.assertEquals
import org.junit.Test

class ImpactVerificationTaskSelectorTest {
    private val appDebugTasks = setOf(
        "lintDemoDebug",
        "testDemoDebugUnitTest",
        "lintProdDebug",
        "testProdDebugUnitTest",
        "assembleDemoDebug",
        "assembleProdDebug"
    )

    @Test
    fun compileOnlyMainSourceChangesAssembleOnlyAffectedAndroidDebugVariants() {
        assertEquals(
            listOf(":core:model:assembleDemoDebug", ":core:model:assembleProdDebug"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":core:model",
                pluginIds = setOf("com.android.library"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf("core/model/src/main/kotlin/com/example/Property.kt"),
                compileOnly = true
            )
        )
    }

    @Test
    fun compileOnlyFlavorSourceChangesAssembleOnlyThatFlavor() {
        assertEquals(
            listOf(":app:assembleDemoDebug"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":app",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf("app/src/demo/java/com/example/DemoOnly.kt"),
                compileOnly = true
            )
        )
    }

    @Test
    fun compileOnlyJvmChangesCompileClassesWithoutRunningUnitTests() {
        assertEquals(
            listOf(":lint:classes"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":lint",
                pluginIds = setOf("org.jetbrains.kotlin.jvm"),
                registeredTaskNames = setOf("classes", "test", "jar"),
                changedFiles = listOf("lint/src/main/kotlin/com/example/Detector.kt"),
                compileOnly = true
            )
        )
    }

    @Test
    fun selectsDemoDebugTasksForDemoFlavorSources() {
        assertEquals(
            listOf(":app:lintDemoDebug", ":app:testDemoDebugUnitTest"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":app",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf("app/src/testDemo/java/com/example/DemoTest.kt")
            )
        )
    }

    @Test
    fun selectsProdDebugTasksForProductionTestSources() {
        assertEquals(
            listOf(":core:network:lintProdDebug", ":core:network:testProdDebugUnitTest"),
            ImpactVerificationTaskSelector.select(
                projectPath = ":core:network",
                pluginIds = setOf("com.android.library", "org.jetbrains.kotlin.android"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf(
                    "core/network/src/testProd/java/com/example/ProductionTest.kt"
                )
            )
        )
    }

    @Test
    fun sharedSourcesSelectBothDebugFlavors() {
        assertEquals(
            listOf(
                ":core:analytics:lintDemoDebug",
                ":core:analytics:testDemoDebugUnitTest",
                ":core:analytics:lintProdDebug",
                ":core:analytics:testProdDebugUnitTest"
            ),
            ImpactVerificationTaskSelector.select(
                projectPath = ":core:analytics",
                pluginIds = setOf("com.android.library", "org.jetbrains.kotlin.android"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf("core/analytics/src/main/kotlin/com/example/Tracker.kt")
            )
        )
    }

    @Test
    fun mixedFlavorChangesSelectBothDebugFlavors() {
        assertEquals(
            listOf(
                ":app:lintDemoDebug",
                ":app:testDemoDebugUnitTest",
                ":app:lintProdDebug",
                ":app:testProdDebugUnitTest"
            ),
            ImpactVerificationTaskSelector.select(
                projectPath = ":app",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf(
                    "app/src/testDemo/java/com/example/DemoTest.kt",
                    "app/src/testProd/java/com/example/ProdTest.kt"
                )
            )
        )
    }

    @Test
    fun unclassifiedBuildChangesConservativelySelectBothDebugFlavors() {
        assertEquals(
            listOf(
                ":app:lintDemoDebug",
                ":app:testDemoDebugUnitTest",
                ":app:lintProdDebug",
                ":app:testProdDebugUnitTest"
            ),
            ImpactVerificationTaskSelector.select(
                projectPath = ":app",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = appDebugTasks,
                changedFiles = listOf("app/build.gradle.kts")
            )
        )
    }

    @Test
    fun emptyChangeListDefaultsToBothDebugFlavors() {
        assertEquals(
            listOf(
                ":app:lintDemoDebug",
                ":app:testDemoDebugUnitTest",
                ":app:lintProdDebug",
                ":app:testProdDebugUnitTest"
            ),
            ImpactVerificationTaskSelector.select(
                projectPath = ":app",
                pluginIds = setOf("com.android.application"),
                registeredTaskNames = appDebugTasks
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
                registeredTaskNames = setOf("assemble", "lint", "testDemoDebugUnitTest"),
                changedFiles = listOf("benchmark/src/main/kotlin/com/example/Benchmark.kt")
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
                registeredTaskNames = setOf("test", "jar", "assemble"),
                changedFiles = listOf("lint/src/main/kotlin/com/example/Detector.kt")
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
                registeredTaskNames = setOf("help", "tasks"),
                changedFiles = listOf("core/src/main/kotlin/com/example/Core.kt")
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
                registeredTaskNames = setOf("lintProdDebug", "testProdDebugUnitTest"),
                changedFiles = listOf("benchmark/src/testDemo/kotlin/com/example/DemoTest.kt")
            )
        )
    }
}
