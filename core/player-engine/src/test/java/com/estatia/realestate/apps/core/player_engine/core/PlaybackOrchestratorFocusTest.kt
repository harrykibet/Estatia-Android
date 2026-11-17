package com.estatia.realestate.apps.core.player_engine.core

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.estatia.realestate.apps.core.model.common.MediaReference
import com.estatia.realestate.apps.core.model.player.EnvironmentState
import com.estatia.realestate.apps.core.model.property.MediaType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Justification: Required for testing Media3 playback components.
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorFocusTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var player: ExoPlayer
    private lateinit var orchestrator: PlaybackOrchestrator

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Looper::class)
        val looper = mockk<Looper>(relaxed = true)
        every { looper.thread } returns Thread.currentThread()
        every { Looper.getMainLooper() } returns looper
        every { Looper.myLooper() } returns looper

        player = mockk(relaxed = true)
        val managed = mockk<ManagedPlayer>(relaxed = true) { every { this@mockk.player } returns this@PlaybackOrchestratorFocusTest.player }
        val pool = mockk<PlayerPool>(relaxed = true) {
            io.mockk.coEvery { getOrCreate(any(), any(), any(), any(), any(), any(), any()) } returns managed
            every { get("m1") } returns managed
        }
        val env = mockk<com.estatia.realestate.apps.core.player_engine.utils.EnvironmentCoordinator>(relaxed = true) {
            every { environment } returns MutableStateFlow(
                EnvironmentState(isMetered = false, shouldThrottlePerformance = false, estimatedThroughputBps = 1_000_000)
            )
        }

        orchestrator = PlaybackOrchestrator(
            pool = pool,
            bitrateController = mockk(relaxed = true),
            environmentCoordinator = env,
            streamingPipeline = mockk(relaxed = true),
            decoderPolicy = mockk(relaxed = true),
            networkRecovery = mockk(relaxed = true),
            sessionCoordinator = mockk(relaxed = true),
            engineScope = TestScope(dispatcher),
            playerDispatcher = dispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun startPlaying() {
        orchestrator.play("m1", MediaReference("http://x/1"), MediaType.VOD, 0.5f, null, null)
        every { player.isPlaying } returns true
    }

    @Test
    fun `transient loss pauses and gain resumes playback that was playing`() = runTest {
        startPlaying()
        orchestrator.pauseForFocusLoss(resumeWhenGained = true)
        verify { player.pause() }

        every { player.isPlaying } returns false
        orchestrator.resumeAfterFocusGain()
        verify(atLeast = 2) { player.play() } // once from play(), once from the resume
    }

    @Test
    fun `gain does not resume playback the user had paused`() = runTest {
        startPlaying()
        every { player.isPlaying } returns false // user paused before the loss
        orchestrator.pauseForFocusLoss(resumeWhenGained = true)

        orchestrator.resumeAfterFocusGain()
        verify(exactly = 1) { player.play() } // only the initial play()
    }

    @Test
    fun `permanent loss never auto-resumes`() = runTest {
        startPlaying()
        orchestrator.pauseForFocusLoss(resumeWhenGained = false)
        every { player.isPlaying } returns false

        orchestrator.resumeAfterFocusGain()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `an explicit pause cancels a pending focus resume`() = runTest {
        startPlaying()
        orchestrator.pauseForFocusLoss(resumeWhenGained = true)
        orchestrator.pauseCurrentPlayer()
        every { player.isPlaying } returns false

        orchestrator.resumeAfterFocusGain()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `duck lowers volume and unduck restores the original`() = runTest {
        startPlaying()
        var volume = 0.8f
        every { player.volume } answers { volume }
        every { player.volume = any() } answers { volume = firstArg() }

        orchestrator.duck()
        assertEquals(0.16f, volume, 0.001f)

        // A repeated duck must not compound or lose the original volume.
        orchestrator.duck()
        orchestrator.unduck()
        assertEquals(0.8f, volume, 0.001f)
    }
}
