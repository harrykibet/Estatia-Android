package com.estatia.realestate.apps.benchmark

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolves the application under measurement, not the benchmark instrumentation APK.
 *
 * The Baseline Profile Gradle plugin supplies this value through the AndroidX
 * Benchmark instrumentation argument. In a self-instrumenting :com.android.test
 * module, InstrumentationRegistry.targetContext.packageName identifies the
 * benchmark APK and must not be passed to MacrobenchmarkRule or BaselineProfileRule.
 */
internal object BenchmarkTargetPackage {

    private const val TARGET_PACKAGE_ARGUMENT = "androidx.benchmark.targetPackageName"

    fun requirePackageName(): String {
        val targetPackageName = InstrumentationRegistry
            .getArguments()
            .getString(TARGET_PACKAGE_ARGUMENT)
            ?.trim()
            .orEmpty()

        require(targetPackageName.isNotEmpty()) {
            """
            Missing required instrumentation argument: $TARGET_PACKAGE_ARGUMENT.

            Run this benchmark through the AndroidX Baseline Profile Gradle plugin,
            which supplies the target application package name.
            """.trimIndent()
        }

        val instrumentationPackageName = InstrumentationRegistry
            .getInstrumentation()
            .context
            .packageName

        check(targetPackageName != instrumentationPackageName) {
            """
            The benchmark target package resolved to the instrumentation APK package.

            Target package: $targetPackageName
            Instrumentation package: $instrumentationPackageName

            This would cause AndroidX Benchmark to force-stop its own instrumentation
            process instead of the application being measured.
            """.trimIndent()
        }

        return targetPackageName
    }
}
