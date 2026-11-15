plugins {
   alias(libs.plugins.estatia.android.core)
   alias(libs.plugins.estatia.firebase)
}

android {
    namespace = "com.estatia.realestate.apps.core.analytics"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.domain)
    implementation(projects.core.model)

    implementation(libs.work.runtime.ktx)
    implementation(libs.bundles.analytics)
    // Prometheus core's StableApi marker is published in a separate annotations artifact.
    implementation(libs.prometheus.metrics.annotations)
    implementation(libs.bundles.firebase)
    implementation(projects.core.common)
    api(libs.protobufJava)
    testImplementation(libs.protobufJava)
}
