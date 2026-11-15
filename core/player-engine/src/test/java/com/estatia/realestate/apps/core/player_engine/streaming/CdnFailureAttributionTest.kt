package com.estatia.realestate.apps.core.player_engine.streaming

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.estatia.realestate.apps.core.model.system.NetworkState
import io.mockk.mockk
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
        // Receiving any HTTP response proves the device reached the endpoint, so a stale
        // "offline" reading must not hide a genuine server-side failure.
        val response = mockk<HttpDataSource.InvalidResponseCodeException>(relaxed = true)

        assertTrue(CdnFailureAttribution.isCdnAtFault(response, NetworkState.NoInternet))
    }
}
