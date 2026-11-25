package com.estatia.realestate.apps.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
        // This is a frame-timing benchmark, not an app-startup benchmark.
        // StartupMode.COLD kills the target process after setupBlock and before
        // measureBlock, leaving no live feed to scroll. Keep the default null.
        setupBlock = {
            val setupDevice = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation(),
            )
            BaselineProfileGenerator.BenchmarkAuthentication.prepareProductionSession(setupDevice)
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Require Estatia's actual property feed before collecting frame timings.
        // A different scrollable surface (or no loaded feed) must not produce a
        // misleadingly successful benchmark.
        requireNotNull(
            device.wait(
                Until.findObject(By.res(PROPERTY_FEED_RESOURCE_ID)),
                FEED_TIMEOUT_MS,
            ),
        ) {
            "Estatia's PropertyFeed did not appear within ${FEED_TIMEOUT_MS / 1_000}s after cold startup."
        }

        val firstPage = requireNotNull(
            device.wait(
                Until.findObject(By.res(FIRST_PAGE_RESOURCE_ID)),
                FEED_TIMEOUT_MS,
            ),
        ) {
            "The first PropertyFeed page did not appear after cold startup."
        }
        firstPage.setGestureMargin(device.displayWidth / 5)
        firstPage.swipe(Direction.UP, FEED_PAGE_SWIPE_PERCENT)

        val secondPage = requireNotNull(
            device.wait(
                Until.findObject(By.res(SECOND_PAGE_RESOURCE_ID)),
                FEED_TIMEOUT_MS,
            ),
        ) {
            "The second PropertyFeed page did not appear after the upward swipe. " +
                "The benchmark account must contain at least two readable listings."
        }
        secondPage.swipe(Direction.DOWN, FEED_PAGE_SWIPE_PERCENT)

        requireNotNull(
            device.wait(
                Until.findObject(By.res(FIRST_PAGE_RESOURCE_ID)),
                FEED_TIMEOUT_MS,
            ),
        ) {
            "The PropertyFeed did not return to its first page after the downward swipe."
        }
        device.waitForIdle()
    }

    private companion object {
        const val PROPERTY_FEED_RESOURCE_ID = "PropertyFeed"
        const val FIRST_PAGE_RESOURCE_ID = "PropertyFeedPage_0"
        const val SECOND_PAGE_RESOURCE_ID = "PropertyFeedPage_1"
        const val FEED_TIMEOUT_MS = 15_000L
        const val FEED_PAGE_SWIPE_PERCENT = 0.70f
    }
}
