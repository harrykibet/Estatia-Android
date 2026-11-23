plugins {
    alias(libs.plugins.estatia.android.application)
}

val firebaseEmulatorIntegration =
    providers.gradleProperty("firebaseEmulatorIntegration").orNull?.toBoolean() ?: false

android {
    namespace = "com.estatia.realestate.apps"

    defaultConfig {
        buildConfigField(
            "boolean",
            "FIREBASE_EMULATOR_INTEGRATION",
            firebaseEmulatorIntegration.toString(),
        )
    }
}

 dokka {
     moduleName.set("RealEstateApp")
     dokkaSourceSets {
         configureEach {
             suppress.set(true)
             skipEmptyPackages.set(true)
             reportUndocumented.set(false)
         }
     }
 }

 hilt {
     enableAggregatingTask = true
 }

dependencies {
    constraints {
        // Android must use Guava's Android flavor. 33.8.0 includes the security
        // fixes required for CVE-2023-2976, CVE-2020-8908 and CVE-2026-102554.
        implementation("com.google.guava:guava") {
            version {
                strictly("33.8.0-android")
            }
            because(
                "Keep Android runtime variants on the patched Guava 33.8.0 Android artifact."
            )
        }
    }

    implementation(libs.bundles.android)
    implementation(libs.core.splashscreen)

    implementation(libs.bundles.lifecycle)

    implementation(libs.kotlinx.datetime)

    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.metrics.performance)

    implementation(libs.firebase.config)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.appcheck.playintegrity)

    implementation(libs.bundles.bouncy)

    implementation(libs.bundles.navigation)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.media3.common)

    baselineProfile(projects.benchmark)

    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.navigationSuite)
    implementation(libs.androidx.compose.material3.windowSizeClass)

    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(platform(libs.firebase.bom))
    androidTestImplementation(libs.firebase.auth)
    androidTestImplementation(libs.firebase.firestore)
    androidTestImplementation(libs.firebase.storage)
    // Keep AndroidJUnitRunner and its test-storage implementation on the instrumentation APK classpath.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.glide)
    ksp(libs.glide.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.domain)
    implementation(projects.core.common)
    implementation(projects.core.config)
    implementation(projects.core.network)
    testImplementation(testFixtures(projects.core.testingNetwork))
    testImplementation(testFixtures(projects.core.testingPlayer))
    androidTestImplementation(testFixtures(projects.core.testingNetwork))
    androidTestImplementation(testFixtures(projects.core.testingPlayer))
    
    implementation(projects.core.playerUi)
    implementation(projects.core.security)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.analytics)
    implementation(projects.core.navigation)
    implementation(projects.core.localization)
    implementation(projects.core.playerEngine)
    implementation(projects.core.designSystem)
    implementation(projects.core.notifications)
    implementation(projects.core.intelligence)

    implementation(projects.feature.auth)
    implementation(projects.feature.home)
    implementation(projects.feature.chats)
    implementation(projects.feature.market)
    implementation(projects.feature.search)
    implementation(projects.feature.profile)
    implementation(projects.feature.comments)
    implementation(projects.feature.property)
    implementation(projects.feature.settings)
    implementation(projects.feature.payments)
    implementation(projects.feature.sharedUi)
    implementation(projects.feature.favorites)
}


