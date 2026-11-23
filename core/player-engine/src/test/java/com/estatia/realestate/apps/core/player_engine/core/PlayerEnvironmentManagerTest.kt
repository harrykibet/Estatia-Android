package com.estatia.realestate.apps.core.player_engine.core

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.model.player.EnvironmentState
import com.estatia.realestate.apps.core.player_engine.utils.EnvironmentCoordinator
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Justification: Required for testing Media3 playback components.
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerEnvironmentManagerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val env = MutableStateFlow(visible())
    private lateinit var pool: PlayerPool
    private lateinit var manager: PlayerEnvironmentManager

    private var backgrounded = 0
    private var foregrounded = 0
    private var reclaimed = 0

    private fun visible() = EnvironmentState(
        isMetered = false,
        shouldThrottlePerformance = false,
        estimatedThroughputBps = 1_000_000L,
        isAppVisible = true,
        isInteractive = true
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Looper::class)
        val looper = mockk<Looper>(relaxed = true)
        every { looper.thread } returns Thread.currentThread()
        every { Looper.getMainLooper() } returns looper
        every { Looper.myLooper() } returns looper

        pool = mockk(relaxed = true)
        val coordinator = mockk<EnvironmentCoordinator>(relaxed = true) {
            every { environment } returns env
        }
        manager = PlayerEnvironmentManager(
            pool = pool,
            environmentCoordinator = coordinator,
            sizingPolicy = mockk(relaxed = true),
            dynamicBitrateController = mockk(relaxed = true),
            engineScope = scope,
            playerDispatcher = dispatcher
        )
        manager.start(
            onAppBackgrounded = { backgrounded++ },
            onAppForegrounded = { foregrounded++ },
            onResourcesReclaimed = { reclaimed++ }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `repeated emissions while backgrounded do not re-run the background handler`() = scope.runTest {
        env.value = visible().copy(isInteractive = false)
        // Further environment changes while still backgrounded (e.g. bandwidth, visibility).
        env.value = env.value.copy(isAppVisible = false)
        env.value = env.value.copy(estimatedThroughputBps = 2_000_000L)

        assertEquals(1, backgrounded)
    }

    @Test
    fun `returning to the foreground notifies once and a later background fires again`() = scope.runTest {
        env.value = visible().copy(isAppVisible = false)
        env.value = visible()
        assertEquals(1, foregrounded)

        env.value = visible().copy(isAppVisible = false)
        assertEquals(2, backgrounded)
    }

    @Test
    fun `resources are reclaimed after the grace period and not if the app returns first`() = scope.runTest {
        env.value = visible().copy(isAppVisible = false)
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(0, reclaimed)

        env.value = visible()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, reclaimed)

        env.value = visible().copy(isAppVisible = false)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, reclaimed)
        verify { pool.releaseAll() }
    }
}
