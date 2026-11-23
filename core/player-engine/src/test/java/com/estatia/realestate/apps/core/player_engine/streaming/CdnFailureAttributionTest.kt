package com.estatia.realestate.apps.core.player_engine.streaming

import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.model.system.NetworkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

// Justification: Required for testing Media3 playback components.
@UnstableApi
class CdnFailureAttributionTest {

    @Test
    fun `io failure while offline is not attributed to the CDN`() {
        assertFalse(CdnFailureAttribution.isCdnAtFault(UnknownHostException("cdn"), NetworkState.NoInternet))
        assertFalse(CdnFailureAttribution.isCdnAtFault(IOException("timeout"), NetworkState.NoInternet))
    }

    @Test
    fun `io failure while connected is attributed to the CDN`() {
        assertTrue(CdnFailureAttribution.isCdnAtFault(IOException("reset"), NetworkState.Connected))
    }

    @Test
    fun `io failure on a poor connection is still attributed to the CDN`() {
        // A poor connection is still a connection: only a complete absence of one excuses the CDN.
        assertTrue(CdnFailureAttribution.isCdnAtFault(IOException("timeout"), NetworkState.PoorConnection))
    }

    @Test
    fun `http error response is attributed to the CDN even if connectivity reads offline`() {
        // Receiving an HTTP response proves the endpoint was reachable, even if connectivity is stale.
        assertTrue(isCdnHttpStatusAtFault(503))
    }

    @Test
    fun `client errors are about the media, not the CDN`() {
        listOf(400, 401, 403, 404, 410).forEach { code ->
            assertFalse("HTTP $code", isCdnHttpStatusAtFault(code))
        }
    }

    @Test
    fun `server errors timeouts and throttling are attributed to the CDN`() {
        listOf(500, 502, 503, 408, 429).forEach { code ->
            assertTrue("HTTP $code", isCdnHttpStatusAtFault(code))
        }
    }
}
