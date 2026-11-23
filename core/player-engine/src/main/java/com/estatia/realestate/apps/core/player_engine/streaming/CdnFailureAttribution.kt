package com.estatia.realestate.apps.core.player_engine.streaming

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Policy
import com.estatia.realestate.apps.core.model.system.NetworkState
import java.io.IOException

/** Pure HTTP status policy, separated from Android-backed Media3 exception construction. */
internal fun isCdnHttpStatusAtFault(responseCode: Int): Boolean =
    responseCode >= 500 || responseCode == 408 || responseCode == 429

/**
 * Decides whether a failed load should count against a CDN endpoint's health.
 *
 * Three consecutive failures open an endpoint's circuit breaker for a minute. If a load that
 * fails only because the device has no connectivity were attributed to the CDN, a short offline
 * spell (a tunnel, a lift) would open the circuit of every endpoint at once and leave the app
 * with no healthy CDN for up to a minute after the network returns.
 */
// Justification: HttpDataSource is part of the unstable Media3 API surface.
@UnstableApi
@Policy
object CdnFailureAttribution {

    /**
     * @return `true` if [error] says something about the CDN and should be reported to the
     * health monitor; `false` if it is explained by the device being offline.
     */
    fun isCdnAtFault(error: IOException, networkState: NetworkState): Boolean {
        // Any HTTP response proves the device reached the CDN, so connectivity is irrelevant.
        // Only server-side statuses say something about its health: a 404/403/410 means this
        // media is missing or forbidden, and counting it would trip the circuit breaker for
        // every video served from that endpoint after three bad links.
        if (error is HttpDataSource.InvalidResponseCodeException) {
            return isCdnHttpStatusAtFault(error.responseCode)
        }
        return networkState !is NetworkState.NoInternet
    }
}
