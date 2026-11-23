package com.estatia.realestate.apps.core.player_engine.core

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Justification: Required for testing Media3 playback components.
@UnstableApi
class NetworkRecoveryCoordinatorTest {

    @Test
    fun `connection failures and timeouts are network errors`() {
        assertTrue(isNetworkErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(isNetworkErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test
    fun `server-side and policy failures are not network errors`() {
        assertFalse(isNetworkErrorCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(isNetworkErrorCode(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED))
    }
}
