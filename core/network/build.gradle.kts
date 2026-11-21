plugins {
    alias(libs.plugins.estatia.android.core)
    alias(libs.plugins.estatia.firebase)
}

android {
    namespace = "com.estatia.realestate.apps.core.network"

    testOptions {
        unitTests {
            all {
                it.useJUnitPlatform()
                // Robolectric uses JDK internals that require explicit module opens
                // on JDK 17+. Keep these flags local to this module's unit tests.
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.logging.interceptor)

    implementation(libs.bundles.bouncy)
    implementation(libs.security.crypto.ktx)

    implementation(libs.caffeine)

    implementation(libs.bundles.analytics)

    implementation(libs.bundles.firebase)
    implementation(libs.appauth)
    implementation(libs.amplify.core)
    implementation(libs.amplify.api)
    implementation(libs.amplify.auth)
    implementation(libs.amplify.storage)
    implementation(libs.amplify.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aws.appconfig)

    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.model)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotest.runner.junit5)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(testFixtures(projects.core.testingNetwork))
    androidTestImplementation(testFixtures(projects.core.testingNetwork))
}
