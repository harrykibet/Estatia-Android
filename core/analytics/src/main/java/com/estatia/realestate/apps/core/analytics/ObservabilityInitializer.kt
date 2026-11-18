package com.estatia.realestate.apps.core.analytics

import com.estatia.realestate.apps.core.common.interfaces.BuildEnvironment
import com.estatia.realestate.apps.core.common.interfaces.IBackendInitializer
import com.estatia.realestate.apps.core.common.interfaces.IDeviceUtils
import com.estatia.realestate.apps.core.domain.security.IAuthRepository
import com.estatia.realestate.apps.core.domain.analytics.ICrashReporter
import com.estatia.realestate.apps.core.domain.config.INetworkConfig
import com.estatia.realestate.apps.core.domain.config.ISecurityConfig
import com.estatia.realestate.apps.core.common.exceptions.getOrNull
import io.micrometer.core.instrument.MeterRegistry
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import javax.inject.Inject

/**
 * Initializes the global observability context (Crashlytics, Metrics, Tracing).
 *
 * OPERATIONAL CONTRACT:
 * - Responsibility: Setup monitoring infrastructure and set baseline device context.
 * - Concurrency: Thread-safe; idempotent initialization.
 * - Resilience: Blocks until [networkConfig] is ready before creating registries.
 * - Performance: Avoids blocking the main thread during heavy registry setup.
 */
@Utility
internal class ObservabilityInitializer @Inject constructor(
    private val crashReporter: ICrashReporter,
    private val deviceUtils: IDeviceUtils,
    private val authRepository: IAuthRepository,
    private val networkConfig: INetworkConfig,
    private val securityConfig: ISecurityConfig,
    private val meterRegistryFactories: Set<@JvmSuppressWildcards MeterRegistryFactory>,
    private val metricsRegistrar: MetricsRegistrar,
    private val buildEnvironment: BuildEnvironment
) : IBackendInitializer {

    // Keep registry instances stable across repeated initialize() calls.
    // The lazy initializer is only reached after networkConfig.awaitReady() below.
    private val meterRegistries: List<MeterRegistry> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        meterRegistryFactories.map { it.create() }
    }

    override suspend fun initialize() {
        // Registry constructors may validate configuration, so wait before invoking factories.
        networkConfig.awaitReady()

        // Set baseline crashlytics keys
        val deviceInfo = deviceUtils.getDeviceInfo()

        crashReporter.setCustomKey("os", deviceInfo.os)
        crashReporter.setCustomKey("device_type", deviceInfo.deviceType)
        crashReporter.setCustomKey("app_version", deviceInfo.appVersion)

        // Track user ID if already logged in
        authRepository.getCurrentUserId().getOrNull()?.let { userId ->
            crashReporter.setCustomKey("user_id", userId)
        }

        // Enable metrics egress for validation in debug builds OR when telemetry is enabled.
        if (buildEnvironment.isDebug || securityConfig.isTelemetryEnabled) {
            meterRegistries.forEach(metricsRegistrar::register)
        }
    }
}
