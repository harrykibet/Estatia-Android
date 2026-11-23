package com.estatia.realestate.apps.core.player_engine.streaming

import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.model.cdn.CdnEndpoint
import com.estatia.realestate.apps.core.player_engine.utils.EnvironmentCoordinator
import com.estatia.realestate.apps.core.model.player.EnvironmentState
import com.estatia.realestate.apps.core.testing.clock.TestClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

// Justification: Required for testing Media3 playback components.
@UnstableApi
class CdnPolicyTest {

    private lateinit var environmentCoordinator: EnvironmentCoordinator
    private lateinit var random: Random
    private lateinit var policy: CdnPolicy
    private val testClock = TestClock(1000L)

    private val environmentFlow = MutableStateFlow(
        EnvironmentState(isMetered = false, shouldThrottlePerformance = false, estimatedThroughputBps = 1000)
    )

    private val endpoints = listOf(
        CdnEndpoint("A", "https://cdn-a.com"),
        CdnEndpoint("B", "https://cdn-b.com")
    )

    @Before
    fun setup() {
        environmentCoordinator = mockk {
            every { environment } returns environmentFlow
        }
        random = mockk {
            every { nextInt(any()) } returns 0
        }
        policy = CdnPolicy(environmentCoordinator, random, clock = { testClock.currentTimeMillis() })
    }

    @Test
    fun `select stable network choose best latency`() = runTest {
        // Given
        val snapshot = mapOf(
            endpoints[0].baseUrl to CdnHealth(latencyMs = 100, 0, 0, null),
            endpoints[1].baseUrl to CdnHealth(latencyMs = 50, 0, 0, null)
        )

        // When
        val selected = policy.select(endpoints, snapshot)

        // Then
        assertEquals(endpoints[1], selected)
    }

    @Test
    fun `select metered network choose random fallback`() = runTest {
        // Given
        environmentFlow.value = environmentFlow.value.copy(isMetered = true)

        // When
        val selected = policy.select(endpoints, emptyMap())

        // Then
        assertEquals(endpoints[0], selected) // random index 0 mocked
    }

    @Test
    fun `select with open circuit skips that endpoint`() = runTest {
        // Given
        val snapshot = mapOf(
            endpoints[0].baseUrl to CdnHealth(latencyMs = 10, 0, 0, testClock.currentTimeMillis() + 10000),
            endpoints[1].baseUrl to CdnHealth(latencyMs = 50, 0, 0, null)
        )

        // When
        val selected = policy.select(endpoints, snapshot)

        // Then
        assertEquals(endpoints[1], selected)
    }

    @Test
    fun `select when all endpoints have open circuits falls back to random`() = runTest {
        // Given
        val snapshot = mapOf(
            endpoints[0].baseUrl to CdnHealth(null, 0, 0, testClock.currentTimeMillis() + 10000),
            endpoints[1].baseUrl to CdnHealth(null, 0, 0, testClock.currentTimeMillis() + 10000)
        )

        // When
        val selected = policy.select(endpoints, snapshot)

        // Then
        assertEquals(endpoints[0], selected)
    }

    @Test
    fun `metered network random fallback never picks an open-circuit endpoint`() = runTest {
        // Given: the random index is mocked to 0, which would be the open endpoint
        // if the circuit state were ignored.
        environmentFlow.value = environmentFlow.value.copy(isMetered = true)
        val snapshot = mapOf(
            endpoints[0].baseUrl to CdnHealth(null, 3, 0, testClock.currentTimeMillis() + 10000)
        )

        // When
        val selected = policy.select(endpoints, snapshot)

        // Then
        assertEquals(endpoints[1], selected)
    }

    @Test
    fun `throttled device random fallback never picks an open-circuit endpoint`() = runTest {
        environmentFlow.value = environmentFlow.value.copy(shouldThrottlePerformance = true)
        val snapshot = mapOf(
            endpoints[0].baseUrl to CdnHealth(null, 3, 0, testClock.currentTimeMillis() + 10000)
        )

        val selected = policy.select(endpoints, snapshot)

        assertEquals(endpoints[1], selected)
    }

    @Test
    fun `metered network with every circuit open still returns an endpoint`() = runTest {
        environmentFlow.value = environmentFlow.value.copy(isMetered = true)
        val snapshot = mapOf(
            endpoints[0].baseUrl to CdnHealth(null, 3, 0, testClock.currentTimeMillis() + 10000),
            endpoints[1].baseUrl to CdnHealth(null, 3, 0, testClock.currentTimeMillis() + 10000)
        )

        val selected = policy.select(endpoints, snapshot)

        // Falls back to the full list rather than failing; index 0 is mocked.
        assertEquals(endpoints[0], selected)
    }

    @Test
    fun `scored fallback after all circuits clear returns to latency based choice`() = runTest {
        val snapshot = mapOf(
            // Circuit already expired (open-until is in the past), so it is eligible again.
            endpoints[0].baseUrl to CdnHealth(latencyMs = 20, 3, 0, testClock.currentTimeMillis() - 1),
            endpoints[1].baseUrl to CdnHealth(latencyMs = 90, 0, 0, null)
        )

        val selected = policy.select(endpoints, snapshot)

        // 20 + 3*250 = 770 vs 90 -> endpoint B still wins on the failure penalty.
        assertEquals(endpoints[1], selected)
    }
}
