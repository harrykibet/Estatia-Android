package com.estatia.realestate.apps.core.player_engine.core

import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.model.common.MediaReference
import com.estatia.realestate.apps.core.model.property.MediaType
import com.estatia.realestate.apps.core.player_engine.utils.EnvironmentCoordinator
import com.estatia.realestate.apps.core.model.player.EnvironmentState
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

// Justification: Required for testing Media3 playback components.
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerTest {

    private lateinit var orchestrator: PlaybackOrchestrator
    private lateinit var sessionCoordinator: MediaSessionCoordinator
    private lateinit var networkRecovery: NetworkRecoveryCoordinator
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var environmentManager: PlayerEnvironmentManager
    private lateinit var environmentCoordinator: EnvironmentCoordinator
    private lateinit var pool: PlayerPool
    private lateinit var playerManager: PlayerManager

    private val testScope = TestScope()
    private val playerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val environmentFlow = MutableStateFlow(
        EnvironmentState(isMetered = false, shouldThrottlePerformance = false, estimatedThroughputBps = 1000000)
    )

    @Before
    @Suppress("UseKtx")
    fun setup() {
        mockkStatic(Uri::class)
        val mockUri = mockk<Uri>(relaxed = true)
        every { Uri.parse(any()) } returns mockUri

        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { mockLooper.thread } returns Thread.currentThread()
        every { Looper.getMainLooper() } returns mockLooper
        every { Looper.myLooper() } returns mockLooper
        
        orchestrator = mockk(relaxed = true)
        sessionCoordinator = mockk(relaxed = true)
        networkRecovery = mockk(relaxed = true)
        audioFocusManager = mockk(relaxed = true)
        environmentManager = mockk(relaxed = true)
        environmentCoordinator = mockk(relaxed = true) {
            every { environment } returns environmentFlow
        }
        pool = mockk(relaxed = true)

        playerManager = PlayerManager(
            orchestrator,
            sessionCoordinator,
            networkRecovery,
            audioFocusManager,
            environmentManager,
            environmentCoordinator,
            pool,
            testScope,
            playerDispatcher
        )
    }

    @Test
    fun `play calls orchestrator play and requests focus`() = testScope.runTest {
        val mediaId = "media_1"
        val uri = MediaReference("")
        val mediaType = MediaType.VOD

        playerManager.play(mediaId, uri, mediaType)

        coVerify { orchestrator.play(mediaId, uri, mediaType, any(), any(), any()) }
        verify { audioFocusManager.request() }
    }

    @Test
    fun `player management handles rapid navigation away safely`() = testScope.runTest {
        val mediaId = "media_race"
        playerManager.play(mediaId, MediaReference(""), MediaType.VOD)
        
        // Rapidly call pause (simulating navigation or screen hidden)
        playerManager.pause()
        
        coVerify { orchestrator.pauseCurrentPlayer() }
    }

    @Test
    fun `pause calls orchestrator pause and abandons focus`() = testScope.runTest {
        playerManager.pause()

        verify { orchestrator.pauseCurrentPlayer() }
        verify { audioFocusManager.abandon() }
    }

    @Test
    fun `foreground after a reclaim rebuilds playback instead of resuming a dead player`() = testScope.runTest {
        val backgrounded = slot<suspend () -> Unit>()
        val foregrounded = slot<suspend () -> Unit>()
        val reclaimed = slot<suspend () -> Unit>()
        verify { environmentManager.start(capture(backgrounded), capture(foregrounded), capture(reclaimed)) }

        every { orchestrator.isCurrentlyPlaying() } returns true
        coEvery { orchestrator.restoreLastPlayback() } returns true

        backgrounded.captured.invoke()
        reclaimed.captured.invoke()
        foregrounded.captured.invoke()

        verify { orchestrator.onPoolReleased() }
        coVerify { orchestrator.restoreLastPlayback() }
        verify(exactly = 0) { orchestrator.resumeCurrentPlayer() }
        verify { audioFocusManager.request() }
    }

    @Test
    fun `foreground without a reclaim resumes the paused player and re-requests focus`() = testScope.runTest {
        val backgrounded = slot<suspend () -> Unit>()
        val foregrounded = slot<suspend () -> Unit>()
        verify { environmentManager.start(capture(backgrounded), capture(foregrounded), any()) }

        every { orchestrator.isCurrentlyPlaying() } returns true
        coEvery { orchestrator.restoreLastPlayback() } returns false

        backgrounded.captured.invoke()
        foregrounded.captured.invoke()

        verify { orchestrator.resumeCurrentPlayer() }
        verify { audioFocusManager.request() }
    }

    @Test
    fun `foreground does not resume playback that was not playing before`() = testScope.runTest {
        val backgrounded = slot<suspend () -> Unit>()
        val foregrounded = slot<suspend () -> Unit>()
        verify { environmentManager.start(capture(backgrounded), capture(foregrounded), any()) }

        every { orchestrator.isCurrentlyPlaying() } returns false

        backgrounded.captured.invoke()
        foregrounded.captured.invoke()

        verify(exactly = 0) { orchestrator.resumeCurrentPlayer() }
        coVerify(exactly = 0) { orchestrator.restoreLastPlayback() }
    }
}
