package com.estatia.realestate.apps.core.analytics.di

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsModuleTest {

    @Test
    fun `prometheus exemplar sampling is disabled without a span context provider`() {
        val properties = requireNotNull(
            createAndroidPrometheusConfig().prometheusProperties()
        ) {
            "Android Prometheus configuration must provide properties"
        }

        assertEquals(
            "false",
            properties.getProperty("io.prometheus.metrics.exemplars_enabled")
        )
    }
}
