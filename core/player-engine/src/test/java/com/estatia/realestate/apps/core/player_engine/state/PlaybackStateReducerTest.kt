package com.estatia.realestate.apps.core.player_engine.state

import android.os.Looper
import android.os.SystemClock
import app.cash.turbine.test
import com.estatia.realestate.apps.core.testing.clock.TestClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateReducerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var reducer: PlaybackStateReducer
    private val testClock = TestClock(0L)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Looper::class)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L

        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper
        every { Looper.myLooper() } returns mockLooper

        reducer = PlaybackStateReducer(testScope, clock = { testClock.currentTimeMillis() })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        assertEquals(PlaybackStateReducer.State.Idle, reducer.state.value)
    }

    @Test
    fun `dispatch BufferingStarted transitions to Buffering`() = runTest {
        reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
        assertEquals(PlaybackStateReducer.State.Buffering, reducer.state.value)
    }

    @Test
    fun `watchdog triggers Error if stuck in Buffering for 7s`() = testScope.runTest {
        reducer.state.test {
            assertEquals(PlaybackStateReducer.State.Idle, awaitItem())

            reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
            assertEquals(PlaybackStateReducer.State.Buffering, awaitItem())

            // Advance time to just before timeout
            advanceTimeBy(6999.milliseconds)
            expectNoEvents()

            // Advance past timeout
            advanceTimeBy(1.milliseconds)
            val state = awaitItem()
            assert(state is PlaybackStateReducer.State.Error)
            assertEquals("Playback attempt timed out (Watchdog)", (state as PlaybackStateReducer.State.Error).error.message)
        }
    }

    @Test
    fun `watchdog is cancelled if BufferingCompleted arrives before timeout`() = testScope.runTest {
        reducer.state.test {
            assertEquals(PlaybackStateReducer.State.Idle, awaitItem())

            reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
            assertEquals(PlaybackStateReducer.State.Buffering, awaitItem())

            advanceTimeBy(5000.milliseconds)
            reducer.dispatch(PlaybackStateReducer.Event.BufferingCompleted)
            assertEquals(PlaybackStateReducer.State.Ready, awaitItem())

            // Advance past the original timeout
            advanceTimeBy(3000.milliseconds)
            expectNoEvents()
        }
    }

    @Test
    fun `rapid toggle Buffering and Ready maintains stability`() = testScope.runTest {
        reducer.state.test {
            awaitItem() // Idle
            repeat(10) {
                reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
                assertEquals(PlaybackStateReducer.State.Buffering, awaitItem())
                reducer.dispatch(PlaybackStateReducer.Event.BufferingCompleted)
                assertEquals(PlaybackStateReducer.State.Ready, awaitItem())
            }
        }
    }

    @Test
    fun `watchdog is cancelled on Reset`() = testScope.runTest {
        reducer.state.test {
            assertEquals(PlaybackStateReducer.State.Idle, awaitItem())

            reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
            assertEquals(PlaybackStateReducer.State.Buffering, awaitItem())

            reducer.dispatch(PlaybackStateReducer.Event.Reset)
            assertEquals(PlaybackStateReducer.State.Idle, awaitItem())

            advanceTimeBy(8000.milliseconds)
            expectNoEvents()
        }
    }

    // region Transition guards and watchdog lifecycle

    @Test
    fun `Pause is ignored while Buffering and the watchdog keeps running`() = testScope.runTest {
        // ExoPlayer reports isPlaying=false together with STATE_BUFFERING when it rebuffers,
        // so a Pause arrives right after BufferingStarted. It must not mask the buffering state.
        reducer.dispatch(PlaybackStateReducer.Event.Play)
        reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
        reducer.dispatch(PlaybackStateReducer.Event.Pause)

        assertEquals(PlaybackStateReducer.State.Buffering, reducer.state.value)

        // The rebuffer is still guarded by the watchdog.
        advanceTimeBy(7001.milliseconds)
        runCurrent()
        assertTrue(reducer.state.value is PlaybackStateReducer.State.Error)
    }

    @Test
    fun `Pause while Playing transitions to Paused`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.Play)
        reducer.dispatch(PlaybackStateReducer.Event.Pause)

        assertEquals(PlaybackStateReducer.State.Paused, reducer.state.value)
    }

    @Test
    fun `Pause after Ended is ignored`() = testScope.runTest {
        // Reaching the end also flips isPlaying to false in the same callback.
        reducer.dispatch(PlaybackStateReducer.Event.Play)
        reducer.dispatch(PlaybackStateReducer.Event.PlaybackEnded)
        reducer.dispatch(PlaybackStateReducer.Event.Pause)

        assertEquals(PlaybackStateReducer.State.Ended, reducer.state.value)
    }

    @Test
    fun `NetworkLost during Buffering cancels the watchdog`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
        advanceTimeBy(3000.milliseconds)
        reducer.dispatch(PlaybackStateReducer.Event.NetworkLost)
        assertEquals(PlaybackStateReducer.State.Reconnecting, reducer.state.value)

        // Well past the original buffering timeout: still waiting for connectivity,
        // not timed out by the (cancelled) watchdog.
        advanceTimeBy(29_000.milliseconds)
        runCurrent()
        assertEquals(PlaybackStateReducer.State.Reconnecting, reducer.state.value)
    }

    @Test
    fun `Reconnecting gives up with a network error after the reconnect timeout`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.NetworkLost)

        advanceTimeBy(30_001.milliseconds)
        runCurrent()

        val state = reducer.state.value
        assertTrue(state is PlaybackStateReducer.State.Error)
        assertEquals(
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            (state as PlaybackStateReducer.State.Error).error.errorCode
        )
    }

    @Test
    fun `NetworkRestored cancels the reconnect timer`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.NetworkLost)
        advanceTimeBy(10_000.milliseconds)
        reducer.dispatch(PlaybackStateReducer.Event.NetworkRestored)
        reducer.dispatch(PlaybackStateReducer.Event.BufferingCompleted)

        advanceTimeBy(60_000.milliseconds)
        runCurrent()
        assertEquals(PlaybackStateReducer.State.Ready, reducer.state.value)
    }

    @Test
    fun `Reset leaves Reconnecting without a late timeout error`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.NetworkLost)
        reducer.dispatch(PlaybackStateReducer.Event.Reset)

        advanceTimeBy(60_000.milliseconds)
        runCurrent()
        assertEquals(PlaybackStateReducer.State.Idle, reducer.state.value)
    }

    @Test
    fun `NetworkRestored from Reconnecting resumes buffering under a fresh watchdog`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.NetworkLost)
        reducer.dispatch(PlaybackStateReducer.Event.NetworkRestored)
        assertEquals(PlaybackStateReducer.State.Buffering, reducer.state.value)

        advanceTimeBy(7001.milliseconds)
        runCurrent()
        assertTrue(reducer.state.value is PlaybackStateReducer.State.Error)
    }

    @Test
    fun `NetworkRestored is ignored unless Reconnecting`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.Play)
        reducer.dispatch(PlaybackStateReducer.Event.NetworkRestored)

        assertEquals(PlaybackStateReducer.State.Playing, reducer.state.value)

        // An ignored event must not have started a watchdog either.
        advanceTimeBy(10_000.milliseconds)
        runCurrent()
        assertEquals(PlaybackStateReducer.State.Playing, reducer.state.value)
    }

    @Test
    fun `Play cancels the watchdog`() = testScope.runTest {
        reducer.dispatch(PlaybackStateReducer.Event.BufferingStarted)
        reducer.dispatch(PlaybackStateReducer.Event.Play)
        assertEquals(PlaybackStateReducer.State.Playing, reducer.state.value)

        advanceTimeBy(10_000.milliseconds)
        runCurrent()
        assertEquals(PlaybackStateReducer.State.Playing, reducer.state.value)
    }

    // endregion
}
