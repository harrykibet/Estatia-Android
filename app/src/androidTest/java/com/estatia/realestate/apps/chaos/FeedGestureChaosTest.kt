package com.estatia.realestate.apps.chaos

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.estatia.realestate.apps.MainActivity
import com.estatia.realestate.apps.core.player_engine.core.PlayerManager
import com.estatia.realestate.apps.core.player_engine.core.PlayerPool
import com.estatia.realestate.apps.di.FeedGestureChaosEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FeedGestureChaosTest {

    private companion object {
        const val CHAOS_SEED = 0x5EED
        const val FAST_FLICK_OPERATIONS = 50
        const val REVERSAL_SCROLL_OPERATIONS = 30
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var pool: PlayerPool
    private lateinit var playerManager: PlayerManager
    private lateinit var device: UiDevice
    private lateinit var random: Random

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FeedGestureChaosEntryPoint::class.java
        )
        pool = entryPoint.playerPool()
        playerManager = entryPoint.playerManager()

        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        random = Random(CHAOS_SEED)
    }

    @Test
    fun fastFlickThroughFeed() {
        val width = device.displayWidth
        val height = device.displayHeight
        val centerX = width / 2
        val startY = (height * 0.8).toInt()
        val endY = (height * 0.2).toInt()

        repeat(FAST_FLICK_OPERATIONS) {
            // Use seeded random for deterministic step count
            val steps = random.nextInt(5, 11)
            device.swipe(centerX, startY, centerX, endY, steps)
        }

        // Use explicit synchronization instead of Thread.sleep
        device.waitForIdle()
        composeTestRule.waitForIdle()

        val settledId = playerManager.debugActiveMediaId
        if (settledId != null) {
            assertTrue(
                "Visible video $settledId was evicted during chaos! (Seed=$CHAOS_SEED)",
                pool.debugIsIdActive(settledId)
            )
        }

        assertPoolInvariant(opIndex = FAST_FLICK_OPERATIONS)
    }

    @Test
    fun rapidReversalScroll() {
        val width = device.displayWidth
        val height = device.displayHeight
        val centerX = width / 2
        val startY = (height * 0.8).toInt()
        val endY = (height * 0.2).toInt()

        repeat(REVERSAL_SCROLL_OPERATIONS) { i ->
            // Swipe Up
            device.swipe(centerX, startY, centerX, endY, 10)
            // Swipe Down
            device.swipe(centerX, endY, centerX, startY, 10)

            // Check invariant periodically during stress
            if (i % 5 == 0) {
                assertPoolInvariant(opIndex = i)
            }
        }

        device.waitForIdle()
        composeTestRule.waitForIdle()
        assertPoolInvariant(opIndex = REVERSAL_SCROLL_OPERATIONS)
    }

    private fun assertPoolInvariant(opIndex: Int) {
        val currentCount = pool.debugPlayerCount
        val maxAllowed = pool.debugMaxPoolSize + 2
        val context = "Seed=$CHAOS_SEED, Op=$opIndex"

        assertTrue(
            "Pool size ($currentCount) exceeded max allowed ($maxAllowed) - $context",
            currentCount <= maxAllowed
        )
        assertFalse(
            "Duplicate player instances detected in pool - $context",
            pool.debugHasDuplicateInstances()
        )
    }
}
