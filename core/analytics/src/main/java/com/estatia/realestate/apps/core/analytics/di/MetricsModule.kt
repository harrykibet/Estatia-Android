package com.estatia.realestate.apps.core.analytics.di

import com.estatia.realestate.apps.core.analytics.MetricsRegistrar
import com.estatia.realestate.apps.core.analytics.MicrometerMetricsRegistrar
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.Properties
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility

@Module
@InstallIn(SingletonComponent::class)
@Utility
internal abstract class MetricsModule {

    @Binds
    @Singleton
    abstract fun bindMetricsRegistrar(
        registrar: MicrometerMetricsRegistrar
    ): MetricsRegistrar

    companion object {
        @Provides
        @IntoSet
        fun providePrometheusMeterRegistry(): MeterRegistry =
            PrometheusMeterRegistry(createAndroidPrometheusConfig())
    }
}

/**
 * Prometheus exemplars need a configured tracing SpanContext provider.
 * Estatia currently has no current-span bridge for the Prometheus registry,
 * so disable exemplar sampling until that integration is deliberately added.
 */
internal fun createAndroidPrometheusConfig(): PrometheusConfig =
    object : PrometheusConfig {
        override fun get(key: String): String? = null

        override fun prometheusProperties(): Properties =
            Properties().apply {
                super<PrometheusConfig>.prometheusProperties()?.let { putAll(it) }
                setProperty("io.prometheus.metrics.exemplars_enabled", "false")
            }
    }
