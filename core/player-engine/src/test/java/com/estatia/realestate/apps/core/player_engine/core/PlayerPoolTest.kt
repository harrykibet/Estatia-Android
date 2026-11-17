package com.estatia.realestate.apps.core.player_engine.core

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.model.property.MediaType
import com.estatia.realestate.apps.core.model.common.MediaReference
import com.estatia.realestate.apps.core.player_engine.analytics.PlaybackAnalyticsListener
import com.estatia.realestate.apps.core.player_engine.configuration.IPlayerConfigurationFactory
import com.estatia.realestate.apps.core.player_engine.utils.EnvironmentCoordinator
import com.estatia.realestate.apps.core.player_engine.utils.IPlayerPoolSizingPolicy
import com.estatia.realestate.apps.core.domain.config.IPlayerTuningConfig
import com.estatia.realestate.apps.core.model.config.PlayerTuningConfig
import com.estatia.realestate.apps.core.model.player.EnvironmentState
import androidx.media3.exoplayer.ExoPlayer
import com.estatia.realestate.apps.core.player_engine.configuration.PlayerConfiguration
import androidx.media3.common.Player
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

// Justification: Required for testing Media3 playback components.
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPoolTest {

    private lateinit var pool: PlayerPool
    private lateinit var playerFactory: PlayerFactory
    private lateinit var configurationFactory: IPlayerConfigurationFactory
    private lateinit var analyticsListenerProvider: Provider<PlaybackAnalyticsListener>
    private lateinit var environmentCoordinator: EnvironmentCoordinator
    private lateinit var config: IPlayerTuningConfig
    private lateinit var sizingPolicy: IPlayerPoolSizingPolicy
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    @Suppress("UseKtx")
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { mockLooper.thread } returns Thread.currentThread()
        every { Looper.getMainLooper() } returns mockLooper
        every { Looper.myLooper() } returns mockLooper

        playerFactory = mockk(relaxed = true)
        configurationFactory = mockk(relaxed = true)
        analyticsListenerProvider = mockk {
            every { get() } returns mockk(relaxed = true)
        }
        environmentCoordinator = mockk(relaxed = true) {
            every { environment.value } returns EnvironmentState(
                isMetered = false,
                shouldThrottlePerformance = false,
                estimatedThroughputBps = 10_000_000L
            )
        }
        val metricsTracker = mockk<com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker>(relaxed = true)
        config = mockk(relaxed = true) {
            every { playerTuning } returns PlayerTuningConfig()
        }
        sizingPolicy = mockk {
            every { calculateMaxPoolSize(any()) } returns 3
        }

        pool = PlayerPool(
            playerFactory,
            configurationFactory,
            analyticsListenerProvider,
            environmentCoordinator,
            config,
            metricsTracker,
            testScope,
            sizingPolicy
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `pool size is initially empty`() {
        assertEquals(0, pool.debugPlayerCount)
    }

    @Test
    fun `pool handles memory exhaustion gracefully`() = runTest {
        val uri = MediaReference("http://test.com")
        // Given: Memory is tight, pool size limited to 1
        pool.updateMaxPoolSize(1, emptySet())
        
        pool.getOrCreate("id1", uri, MediaType.VOD)
        pool.getOrCreate("id2", uri, MediaType.VOD)
        
        // Then: Pool should have evicted id1 to stay at size 1
        assertEquals(1, pool.debugPlayerCount)
    }

    @Test
    fun `getOrCreate adding new player increases count`() = runTest {
        val mediaId = "id1"
        val uri = MediaReference("http://test.com")
        
        pool.getOrCreate(mediaId, uri, MediaType.VOD)
        
        assertEquals(1, pool.debugPlayerCount)
    }

    @Test
    fun `pool respects maxPoolSize via eviction`() = runTest {
        val uri = MediaReference("http://test.com")
        
        pool.getOrCreate("id1", uri, MediaType.VOD)
        pool.getOrCreate("id2", uri, MediaType.VOD)
        pool.getOrCreate("id3", uri, MediaType.VOD)
        
        assertEquals(3, pool.debugPlayerCount)
        
        // This should evict "id1" (least recently used)
        pool.getOrCreate("id4", uri, MediaType.VOD)
        
        assertEquals(3, pool.debugPlayerCount)
        assertEquals(null, pool.get("id1"))
    }

    @Test
    fun `release during in-flight creation discards the player instead of registering it`() = runTest {
        val uri = MediaReference("http://test.com")
        val gate = CompletableDeferred<Unit>()
        val configuration = mockk<PlayerConfiguration>(relaxed = true)
        // Hold creation open at its first real suspension point.
        coEvery {
            configurationFactory.create(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            gate.await()
            configuration
        }

        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            pool.prewarm("id1", uri, MediaType.VOD)
        }

        // The creation is registered but has nothing in `players` to release yet.
        pool.release("id1")
        gate.complete(Unit)

        val result = pending.await()

        assertTrue(result is PrewarmResult.Failure)
        assertTrue((result as PrewarmResult.Failure).throwable is PlayerReleasedDuringCreationException)
        assertEquals(0, pool.debugPlayerCount)
        assertFalse(pool.debugIsIdActive("id1"))
    }

    @Test
    fun `release mark does not leak into a later creation of the same media`() = runTest {
        val uri = MediaReference("http://test.com")
        val gate = CompletableDeferred<Unit>()
        val configuration = mockk<PlayerConfiguration>(relaxed = true)
        coEvery {
            configurationFactory.create(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            gate.await()
            configuration
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) { pool.prewarm("id1", uri, MediaType.VOD) }
        pool.release("id1")
        gate.complete(Unit)
        first.await()

        // A fresh request for the same media after the release must succeed.
        val second = pool.prewarm("id1", uri, MediaType.VOD)

        assertTrue(second is PrewarmResult.Success)
        assertTrue(pool.debugIsIdActive("id1"))
    }

    @Test
    fun `rejected non-urgent prewarm releases the analytics listener of the discarded player`() = runTest {
        val uri = MediaReference("http://test.com")
        pool.updateMaxPoolSize(1, emptySet())

        val keptPlayer = mockk<ExoPlayer>(relaxed = true)
        val keptListener = mockk<PlaybackAnalyticsListener>(relaxed = true)
        val discardedPlayer = mockk<ExoPlayer>(relaxed = true)
        val discardedListener = mockk<PlaybackAnalyticsListener>(relaxed = true)
        every { playerFactory.create(any()) } returnsMany listOf(
            PlayerFactory.CreatedPlayer(keptPlayer, keptListener),
            PlayerFactory.CreatedPlayer(discardedPlayer, discardedListener)
        )

        pool.getOrCreate("id1", uri, MediaType.VOD)
        val result = pool.prewarm("id2", uri, MediaType.VOD, urgent = false)

        assertEquals(PrewarmResult.Rejected, result)
        verify(exactly = 1) { discardedListener.release() }
        verify(exactly = 1) { discardedPlayer.release() }
        // The player that was admitted is untouched.
        verify(exactly = 0) { keptListener.release() }
        verify(exactly = 0) { keptPlayer.release() }
    }

    @Test
    fun `urgent request survives its own trim when every other player is pinned`() = runTest {
        val uri = MediaReference("http://test.com")
        pool.updateMaxPoolSize(1, emptySet())
        pool.updatePinnedIds(setOf("id1"))

        pool.getOrCreate("id1", uri, MediaType.VOD)
        val urgent = pool.getOrCreate("id2", uri, MediaType.VOD)

        // The urgent player must not have been evicted (released) before the caller got it.
        assertTrue(pool.debugIsIdActive("id2"))
        assertTrue(pool.debugIsIdActive("id1"))
        assertEquals(2, pool.debugPlayerCount)
        verify(exactly = 0) { urgent.player.release() }
    }

    @Test
    fun `observePlayer follows the player through creation and release`() = runTest {
        val uri = MediaReference("http://test.com")
        val player = mockk<ExoPlayer>(relaxed = true)
        every { playerFactory.create(any()) } returns
            PlayerFactory.CreatedPlayer(player, mockk(relaxed = true))

        val seen = mutableListOf<Player?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            pool.observePlayer("id1").toList(seen)
        }

        // Nothing registered yet.
        assertEquals(listOf<Player?>(null), seen)

        pool.getOrCreate("id1", uri, MediaType.VOD)
        assertEquals(player, seen.last())

        // Released (e.g. background reclaim): observers must drop the stale instance.
        pool.release("id1")
        assertEquals(null, seen.last())

        job.cancel()
    }

    @Test
    fun `observePlayer emits the replacement after a forced legacy re-create`() = runTest {
        val uri = MediaReference("http://test.com")
        val first = mockk<ExoPlayer>(relaxed = true)
        val second = mockk<ExoPlayer>(relaxed = true)
        every { playerFactory.create(any()) } returnsMany listOf(
            PlayerFactory.CreatedPlayer(first, mockk(relaxed = true)),
            PlayerFactory.CreatedPlayer(second, mockk(relaxed = true))
        )

        val seen = mutableListOf<Player?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            pool.observePlayer("id1").toList(seen)
        }

        pool.getOrCreate("id1", uri, MediaType.VOD)
        pool.getOrCreate("id1", uri, MediaType.VOD, forceLegacy = true)

        // Sequence: nothing, original player, the gap while it is released, then the player
        // bound to the re-created media (which may be the recycled instance or a new one).
        assertEquals(4, seen.size)
        assertEquals(first, seen[1])
        assertEquals(null, seen[2])
        assertTrue(seen[3] != null)

        job.cancel()
    }
}
