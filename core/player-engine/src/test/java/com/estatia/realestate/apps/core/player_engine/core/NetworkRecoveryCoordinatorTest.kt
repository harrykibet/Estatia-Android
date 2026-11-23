package com.estatia.realestate.apps.core.player_engine.core

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Justification: Required for testing Media3 playback components.
@UnstableApi
class NetworkRecoveryCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val coordinator = NetworkRecoveryCoordinator(
        networkStateProvider = mockk(relaxed = true),
        pool = mockk(relaxed = true),
        engineScope = TestScope(dispatcher),
        playerDispatcher = dispatcher
    )

    private fun error(code: Int) = mockk<PlaybackException> {
        every { errorCode } returns code
    }

    @Test
    fun `connection failures and timeouts are network errors`() {
        assertTrue(coordinator.isNetworkError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertTrue(coordinator.isNetworkError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
    }

    @Test
    fun `server-side and policy failures are not network errors`() {
        assertFalse(coordinator.isNetworkError(error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertFalse(coordinator.isNetworkError(error(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED)))
    }
}
