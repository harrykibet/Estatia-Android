package com.estatia.realestate.apps.core.architecture.annotations

import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget

/**
 * Safety & Suppression: Manual overrides for architectural laws.
 */
object Safety {
    /**
     * Explicitly allows a concrete type as a dependency in architectural components.
     * Use this to document and authorize unavoidable infrastructure dependencies.
     */
    @Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    // KSP consumers in downstream modules must be able to inspect this opt-out on
    // inherited declarations from compiled dependencies.
    @Retention(AnnotationRetention.BINARY)
    annotation class AllowedArchitectureDependency(
        val reason: String
    )
}
