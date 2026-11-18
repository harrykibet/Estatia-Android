package com.estatia.realestate.apps.core.analytics

import com.estatia.realestate.apps.core.architecture.annotations.Identity.Contract
import io.micrometer.core.instrument.MeterRegistry

/**
 * Defers registry construction until observability initialization.
 *
 * Registry constructors may validate configuration immediately, so factories must only be
 * invoked after the configuration lifecycle has reported readiness.
 */
@Contract
fun interface MeterRegistryFactory {
    fun create(): MeterRegistry
}
