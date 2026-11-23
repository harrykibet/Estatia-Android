plugins {
    alias(libs.plugins.estatia.android.flavors)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.estatia.realestate.apps.benchmark"

    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Credentials for the production baseline-profile account come from the
        // environment, never from -P command-line properties: argv is visible to every
        // process on the runner and is echoed by Gradle diagnostics.
        providers.environmentVariable("BASELINE_PROFILE_TEST_EMAIL").orNull?.let {
            testInstrumentationRunnerArguments["baselineProfileTestEmail"] = it
        }
        providers.environmentVariable("BASELINE_PROFILE_TEST_PASSWORD").orNull?.let {
            testInstrumentationRunnerArguments["baselineProfileTestPassword"] = it
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.espresso.core)
    implementation(libs.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit.junit)
}