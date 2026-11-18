package com.estatia.realestate.apps.core.network.sources.aws

import com.estatia.realestate.apps.core.common.exceptions.AppException
import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
import com.estatia.realestate.apps.core.model.analytics.AnalyticsEvent as DomainEvent
import com.estatia.realestate.apps.core.network.interfaces.IAnalyticsRemoteDataSource
import javax.inject.Inject
import com.estatia.realestate.apps.core.architecture.annotations.Identity.DataSource

/**
 * Production analytics adapter while the former Pinpoint destination is unavailable.
 *
 * Amazon Pinpoint engagement analytics reached end of support on October 30, 2026.
 * Events are therefore reported as explicitly unavailable instead of calling an
 * unconfigured Amplify.Analytics category and risking an application crash.
 */
@DataSource
internal class AwsAnalyticsRemoteDataSource @Inject constructor(
    private val metricsTracker: IMetricsTracker
) : IAnalyticsRemoteDataSource {

    override suspend fun logEvent(event: DomainEvent): AppResult<Unit> = remoteAnalyticsUnavailable()

    override suspend fun logEvent(
        message: String,
        eventType: String,
        customMetadata: Map<String, String>?
    ): AppResult<Unit> = remoteAnalyticsUnavailable()

    private fun remoteAnalyticsUnavailable(): AppResult<Unit> {
        metricsTracker.incrementCounter(
            "network.analytics.remote_unavailable",
            mapOf("provider" to "amazon_pinpoint", "reason" to "end_of_support")
        )
        return AppResult.Error(
            AppException.Unknown(
                UnsupportedOperationException(
                    "Remote analytics is disabled: Amazon Pinpoint support ended on October 30, 2026."
                )
            )
        )
    }

    @Deprecated("Raw event querying is no longer supported on the client.")
    override suspend fun getEventsForUser(userId: String): AppResult<List<DomainEvent>> =
        AppResult.Success(emptyList())

    @Deprecated("Raw event querying is no longer supported on the client.")
    override suspend fun getEventById(eventId: String): AppResult<DomainEvent?> =
        AppResult.Success(null)

    override fun generateEventId(): String = java.util.UUID.randomUUID().toString()
}
