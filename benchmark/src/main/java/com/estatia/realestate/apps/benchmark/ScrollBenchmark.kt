package com.estatia.realestate.apps.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the scrolling performance of the property feed.
 *
 * This benchmark ensures that as we add more complex UI and features (like video playback),
 * the scrolling remains smooth (no "jank").
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollFeed() = benchmarkRule.measureRepeated(
        packageName = BenchmarkTargetPackage.requirePackageName(),
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Require Estatia's actual property feed before collecting frame timings.
        // A different scrollable surface (or no loaded feed) must not produce a
        // misleadingly successful benchmark.
        val feedList = requireNotNull(
            device.wait(
                Until.findObject(By.res(PROPERTY_FEED_RESOURCE_ID)),
                FEED_TIMEOUT_MS,
            ),
        ) {
            "Estatia's PropertyFeed did not appear within ${FEED_TIMEOUT_MS / 1_000}s after cold startup."
        }

        feedList.setGestureMargin(device.displayWidth / 5)
        feedList.fling(Direction.DOWN)
        device.waitForIdle()
        feedList.fling(Direction.UP)
    }
    private companion object {
        const val PROPERTY_FEED_RESOURCE_ID = "PropertyFeed"
        const val FEED_TIMEOUT_MS = 15_000L
    }
}
