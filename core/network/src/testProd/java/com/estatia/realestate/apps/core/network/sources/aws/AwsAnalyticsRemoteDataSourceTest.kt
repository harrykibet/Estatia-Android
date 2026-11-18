package com.estatia.realestate.apps.core.network.sources.aws

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility

import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Utility
class AwsAnalyticsRemoteDataSourceTest {

    @Test
    fun remoteEventIsReportedUnavailableWithoutCallingAmplify() = runBlocking {
        val metricsTracker = mockk<IMetricsTracker>(relaxed = true)
        val dataSource = AwsAnalyticsRemoteDataSource(metricsTracker)

        val result = dataSource.logEvent(
            message = "feed impression",
            eventType = "feed_impression",
            customMetadata = null
        )

        assertTrue(result is AppResult.Error)
        verify {
            metricsTracker.incrementCounter(
                "network.analytics.remote_unavailable",
                mapOf("provider" to "amazon_pinpoint", "reason" to "end_of_support")
            )
        }
    }
}
