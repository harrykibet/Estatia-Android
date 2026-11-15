package com.estatia.realestate.apps

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.dsl.TestExtension

// AGP 9.x: flavorDimensions / productFlavors removed from CommonExtension.
// Provide typed overloads for app, library, and test modules.

// Android source-set directories are case-sensitive on Linux. Keep the registered
// flavor IDs aligned with the repository's src/demo and src/prod directories.
private fun EstatiaFlavor.gradleFlavorName(): String = name.lowercase()

fun configureFlavors(
    extension: ApplicationExtension,
    flavorConfigurationBlock: ProductFlavor.(flavor: EstatiaFlavor) -> Unit = {}
) {
    extension.apply {
        FlavorDimension.entries.forEach { flavorDimensions += it.name }
        productFlavors {
            EstatiaFlavor.entries.forEach { flavor ->
                register(flavor.gradleFlavorName()) {
                    dimension = flavor.dimension.name
                    flavorConfigurationBlock(this, flavor)
                    (this).let {
                        flavor.applicationIdSuffix?.let { s -> applicationIdSuffix = s }
                        flavor.versionNameSuffix?.let { s -> versionNameSuffix = s }
                    }
                }
            }
        }
    }
}

fun configureFlavors(
    extension: LibraryExtension,
    flavorConfigurationBlock: ProductFlavor.(flavor: EstatiaFlavor) -> Unit = {}
) {
    extension.apply {
        FlavorDimension.entries.forEach { flavorDimensions += it.name }
        productFlavors {
            EstatiaFlavor.entries.forEach { flavor ->
                register(flavor.gradleFlavorName()) {
                    dimension = flavor.dimension.name
                    flavorConfigurationBlock(this, flavor)
                }
            }
        }
    }
}

fun configureFlavors(
    extension: TestExtension,
    flavorConfigurationBlock: ProductFlavor.(flavor: EstatiaFlavor) -> Unit = {}
) {
    extension.apply {
        FlavorDimension.entries.forEach { flavorDimensions += it.name }
        productFlavors {
            EstatiaFlavor.entries.forEach { flavor ->
                register(flavor.gradleFlavorName()) {
                    dimension = flavor.dimension.name
                    flavorConfigurationBlock(this, flavor)
                }
            }
        }
    }
}
