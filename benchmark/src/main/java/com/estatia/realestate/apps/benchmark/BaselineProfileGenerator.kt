package com.estatia.realestate.apps.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates separate Startup Profile and Baseline Profile rules for Estatia.
 *
 * The Prod target signs in with a dedicated Firebase benchmark account before
 * profile collection begins. Sign-in setup is outside collect(), so credentials
 * and login UI are not included in authenticated startup/journey profiles.
 * Demo retains its deterministic demo authentication provider.
 *
 * Credentials are supplied only through instrumentation arguments populated
 * from GitHub Actions secrets. They are never embedded in source or printed.
 *
 * The benchmark is fail-fast. Required UI elements are asserted rather than
 * silently skipped so an incomplete journey cannot produce an incomplete profile.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /**
     * Captures only the authenticated launch path for DEX startup layout optimization.
     *
     * The Startup Profile must remain limited to work required to display the initial
     * authenticated Home experience. Other destinations and post-startup interactions
     * belong in [generateCriticalUserJourneys].
     */
    @Test
    fun generateStartup() = run {
        val setupDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        BenchmarkAuthentication.prepareProductionSession(setupDevice)
        baselineProfileRule.collect(
            packageName = BenchmarkTargetPackage.requirePackageName(),
            includeInStartupProfile = true,
        ) {
        val device = UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation(),
        )

        pressHome()
        startActivityAndWait()
        waitForAuthenticatedNavigationUi(device)

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Initial authenticated Home property feed",
        )
        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED_PAGE_0,
            description = "First authenticated Home property page",
        )

            device.waitForIdle(UI_IDLE_TIMEOUT_MS)
        }
    }

    /**
     * Captures the broader critical user journeys for the Baseline Profile only.
     *
     * These journeys remain part of the Baseline Profile, but must not expand the
     * Startup Profile beyond the initial authenticated Home experience.
     */
    @Test
    fun generateCriticalUserJourneys() = run {
        val setupDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        BenchmarkAuthentication.prepareProductionSession(setupDevice)
        baselineProfileRule.collect(
            packageName = BenchmarkTargetPackage.requirePackageName(),
            includeInStartupProfile = false,
        ) {
        val device = UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation(),
        )

        // ---------------------------------------------------------------------
        // Startup
        // ---------------------------------------------------------------------

        pressHome()
        startActivityAndWait()

        /*
         * Baseline Profile generation must operate on an authenticated
         * application session.
         *
         * We deliberately do not attempt to log in here.
         */
        waitForAuthenticatedNavigationUi(device)

        // ---------------------------------------------------------------------
        // Critical User Journeys
        // ---------------------------------------------------------------------

        exerciseHomeFeed(device)

        exerciseSearch(device)

        exercisePropertyNavigation(device)

        exerciseTopLevelDestinations(device)

        // Always finish on Home so the benchmark ends in a deterministic state.
        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.HOME,
        )

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Home property feed",
        )

            device.waitForIdle(UI_IDLE_TIMEOUT_MS)
        }
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    /**
     * Verifies that the benchmark started with an authenticated session.
     *
     * Production sign-in happens in prepareProductionBenchmarkSession(), before
     * this assertion runs inside the recorded profile journey.
     */
    private fun waitForAuthenticatedNavigationUi(
        device: UiDevice,
    ) {
        val homeNavigationItem = device.wait(
            Until.findObject(
                By.res(NavigationTestTags.HOME),
            ),
            AUTHENTICATION_TIMEOUT_MS,
        )

        if (homeNavigationItem == null) {
            val hierarchyDump = captureWindowHierarchy(device)
            assertNotNull(
                """
                The authenticated Home navigation item was not exposed to UI Automator within
                ${AUTHENTICATION_TIMEOUT_MS / 1_000} seconds.

                This check verifies the navigation UI; it does not independently prove that
                authentication failed. Inspect the captured hierarchy before changing auth
                behavior or adding credentials to this benchmark.

                UI hierarchy captured before benchmark cleanup:
                $hierarchyDump
                """.trimIndent(),
                homeNavigationItem,
            )
        }
    }

    /**
     * Captures the current accessibility hierarchy and returns its contents.
     *
     * Macrobenchmark can uninstall the target and instrumentation APKs during
     * teardown, before the CI workflow can pull files from app-specific storage.
     * Including the XML in the assertion message preserves the evidence in the
     * instrumentation report even when those packages have already been removed.
     */
    private fun captureWindowHierarchy(device: UiDevice): String = try {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val outputDirectory = checkNotNull(testContext.getExternalFilesDir(null)) {
            "External app-specific storage is unavailable for the UI hierarchy dump."
        }
        check(outputDirectory.exists() || outputDirectory.mkdirs()) {
            "Could not create the UI hierarchy output directory: $outputDirectory"
        }

        val outputFile = File(outputDirectory, "baseline-profile-window-hierarchy.xml")
        device.dumpWindowHierarchy(outputFile)
        """
        File: ${outputFile.absolutePath}
        ${outputFile.readText()}
        """.trimIndent()
    } catch (exception: Exception) {
        "UI hierarchy capture failed: ${exception.javaClass.simpleName}: ${exception.message}"
    }

    // =========================================================================
    // Home
    // =========================================================================

    /**
     * Exercises the primary property feed.
     *
     * The property feed is one of the application's primary performance paths
     * because it combines Compose rendering, scrolling, property content and
     * potentially media playback.
     */
    private fun exerciseHomeFeed(
        device: UiDevice,
    ) {
        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.HOME,
        )

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Home property feed gesture surface",
        )

        // Use coordinate-based drags with an explicit duration. UiObject2.swipe()
        // derives its path from accessibility bounds and has intermittently left
        // the pager on the original page while the same journey passed elsewhere.
        swipeFeedPage(
            device = device,
            sourceTag = ScreenTestTags.PROPERTY_FEED_PAGE_0,
            targetTag = ScreenTestTags.PROPERTY_FEED_PAGE_1,
            direction = Direction.UP,
            sourceDescription = "First Home property page",
            targetDescription = "Second Home property page after advancing the feed",
        )
        swipeFeedPage(
            device = device,
            sourceTag = ScreenTestTags.PROPERTY_FEED_PAGE_1,
            targetTag = ScreenTestTags.PROPERTY_FEED_PAGE_0,
            direction = Direction.DOWN,
            sourceDescription = "Second Home property page before returning",
            targetDescription = "Home feed after returning to the first property",
        )
        device.waitForIdle(UI_IDLE_TIMEOUT_MS)
    }

    /**
     * Sends a real screen-coordinate drag and verifies the pager changed pages.
     *
     * A failed gesture is retried with the same bounded motion; the destination
     * tag remains mandatory, so retries cannot turn a non-moving pager into a
     * successful benchmark journey.
     */
    private fun swipeFeedPage(
        device: UiDevice,
        sourceTag: String,
        targetTag: String,
        direction: Direction,
        sourceDescription: String,
        targetDescription: String,
    ) {
        repeat(FEED_PAGE_SWIPE_ATTEMPTS) {
            waitFor(
                device = device,
                tag = sourceTag,
                description = sourceDescription,
            )
            device.waitForIdle(UI_IDLE_TIMEOUT_MS)

            val centerX = device.displayWidth / 2
            val upperY = (device.displayHeight * FEED_SWIPE_UPPER_FRACTION).toInt()
            val lowerY = (device.displayHeight * FEED_SWIPE_LOWER_FRACTION).toInt()
            val startY = if (direction == Direction.UP) lowerY else upperY
            val endY = if (direction == Direction.UP) upperY else lowerY

            device.swipe(centerX, startY, centerX, endY, FEED_PAGE_SWIPE_STEPS)
            if (
                device.wait(
                    Until.findObject(By.res(targetTag)),
                    FEED_PAGE_TRANSITION_TIMEOUT_MS,
                ) != null
            ) {
                return
            }
        }

        waitFor(
            device = device,
            tag = targetTag,
            description = targetDescription,
            timeoutMs = UI_TIMEOUT_MS,
        )
    }

    // =========================================================================
    // Search
    // =========================================================================

    /**
     * Exercises the application's search journey from Home.
     */
    private fun exerciseSearch(
        device: UiDevice,
    ) {
        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.HOME,
        )

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Property feed before opening Search",
        )

        val searchButton = waitFor(
            device = device,
            tag = ScreenTestTags.TOP_APP_BAR_NAVIGATION,
            description = "Home search navigation button",
        )

        searchButton.click()

        val searchField = waitFor(
            device = device,
            tag = ScreenTestTags.SEARCH_FIELD,
            description = "Search text field",
        )

        searchField.text = SEARCH_QUERY

        device.pressEnter()

        device.waitForIdle(UI_IDLE_TIMEOUT_MS)

        /*
         * Search is a separate destination. Return to Home rather than
         * assuming a particular navigation implementation.
         */
        device.pressBack()

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Property feed after returning from Search",
        )
    }

    // =========================================================================
    // Property Details
    // =========================================================================

    /**
     * Exercises property interaction from the vertical property feed.
     *
     * This follows the existing application's feed interaction rather than
     * inventing benchmark-only UI.
     */
    private fun exercisePropertyNavigation(
        device: UiDevice,
    ) {
        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.HOME,
        )

        val feed = waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Property feed before property interaction",
        )

        /*
         * Estatia's property feed uses gesture-driven content.
         *
         * Keep this interaction deliberately small and deterministic. The
         * objective is to exercise the code path, not perform an exhaustive
         * UI test.
         */
        feed.swipe(
            Direction.RIGHT,
            PROPERTY_SWIPE_PERCENT,
        )

        device.waitForIdle(UI_IDLE_TIMEOUT_MS)

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_DETAILS,
            description = "Property details after swiping from the property feed",
        )

        device.pressBack()

        waitFor(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Property feed after returning from property details",
        )
    }

    // =========================================================================
    // Top-Level Navigation
    // =========================================================================

    /**
     * Exercises every authenticated top-level destination.
     *
     * Navigation is identified by semantic test tags instead of positional
     * indexes such as navItems[0] or navItems[4].
     *
     * TopLevelDestination is the application's source of truth for these
     * destinations.
     */
    private fun exerciseTopLevelDestinations(
        device: UiDevice,
    ) {
        // ---------------------------------------------------------------------
        // Market
        // ---------------------------------------------------------------------

        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.MARKET,
        )

        waitFor(
            device = device,
            tag = ScreenTestTags.MARKET,
            description = "Market screen",
        )

        // ---------------------------------------------------------------------
        // Chats
        // ---------------------------------------------------------------------

        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.CHATS,
        )

        waitFor(
            device = device,
            tag = ScreenTestTags.CHATS,
            description = "Chats screen",
        )

        // ---------------------------------------------------------------------
        // Add Property
        // ---------------------------------------------------------------------

        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.ADD_PROPERTY,
        )

        waitFor(
            device = device,
            tag = ScreenTestTags.ADD_PROPERTY,
            description = "Add Property screen",
        )

        // ---------------------------------------------------------------------
        // Profile
        // ---------------------------------------------------------------------

        navigateTo(
            device = device,
            navigationTag = NavigationTestTags.PROFILE,
        )

        exerciseProfile(device)
    }

    // =========================================================================
    // Profile
    // =========================================================================

    /**
     * Exercises the actual ProfileScreen UI.
     *
     * There is intentionally NO authentication/login interaction here.
     *
     * The authenticated ProfileScreen contains:
     *
     * - Profile information
     * - Statistics
     * - Edit Profile
     * - Share
     * - Listings
     * - Favorites
     * - Reviews
     *
     * The profile content tabs are therefore the appropriate representative
     * Profile CUJ for this Baseline Profile.
     */
    private fun exerciseProfile(
        device: UiDevice,
    ) {
        waitFor(
            device = device,
            tag = ScreenTestTags.PROFILE,
            description = "Profile screen",
        )

        val listingsTab = waitFor(
            device = device,
            tag = ProfileTestTags.LISTINGS,
            description = "Profile Listings tab",
        )

        val favoritesTab = waitFor(
            device = device,
            tag = ProfileTestTags.FAVORITES,
            description = "Profile Favorites tab",
        )

        val reviewsTab = waitFor(
            device = device,
            tag = ProfileTestTags.REVIEWS,
            description = "Profile Reviews tab",
        )

        // Listings
        listingsTab.click()
        device.waitForIdle(UI_IDLE_TIMEOUT_MS)

        // Favorites
        favoritesTab.click()
        device.waitForIdle(UI_IDLE_TIMEOUT_MS)

        // Reviews
        reviewsTab.click()
        device.waitForIdle(UI_IDLE_TIMEOUT_MS)

        // Restore the default Profile tab.
        listingsTab.click()
        device.waitForIdle(UI_IDLE_TIMEOUT_MS)
    }

    // =========================================================================
    // Navigation Helpers
    // =========================================================================

    /**
     * Navigates to a top-level destination using its semantic test tag.
     *
     * We intentionally avoid relying on the order of TopLevelDestination.entries
     * or the index of elements returned by UiDevice.findObjects().
     */
    private fun navigateTo(
        device: UiDevice,
        navigationTag: String,
    ) {
        val navigationItem = waitFor(
            device = device,
            tag = navigationTag,
            description = "Navigation destination: $navigationTag",
        )

        navigationItem.click()

        device.waitForIdle(UI_IDLE_TIMEOUT_MS)
    }

    // =========================================================================
    // UI Synchronization
    // =========================================================================

    /**
     * Waits for a required UI element and fails the benchmark if it does not
     * appear within the timeout.
     *
     * Returning nullable UI objects and silently continuing is intentionally
     * avoided because that can generate an incomplete Baseline Profile.
     */
    private fun waitFor(
        device: UiDevice,
        tag: String,
        description: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): UiObject2 {
        val object2 = device.wait(
            Until.findObject(By.res(tag)),
            timeoutMs,
        )

        if (object2 == null) {
            val hierarchyDump = captureWindowHierarchy(device)
            assertNotNull(
                """
                Required benchmark UI was not found.

                Description: $description
                Resource ID: $tag
                Timeout: ${timeoutMs / 1_000}s
                UI hierarchy captured before benchmark cleanup:
                $hierarchyDump
                """.trimIndent(),
                object2,
            )
        }

        return checkNotNull(object2)
    }

    private companion object {
        const val AUTHENTICATION_TIMEOUT_MS = 60_000L

        const val UI_TIMEOUT_MS = 15_000L

        const val UI_IDLE_TIMEOUT_MS = 5_000L

        const val SEARCH_QUERY = "Nairobi"

        const val PROPERTY_SWIPE_PERCENT = 1.0f

        const val FEED_PAGE_SWIPE_ATTEMPTS = 2
        const val FEED_PAGE_SWIPE_STEPS = 50
        const val FEED_PAGE_TRANSITION_TIMEOUT_MS = 3_000L
        const val FEED_SWIPE_UPPER_FRACTION = 0.25f
        const val FEED_SWIPE_LOWER_FRACTION = 0.75f
    }
}

/**
 * Stable semantic identifiers for authenticated top-level navigation.
 *
 * These values correspond to the application's TopLevelDestination enum:
 *
 * HOME
 * ADD_PROPERTY
 * CHATS
 * MARKET
 * PROFILE
 */
internal object NavigationTestTags {

    const val HOME = "EstatiaNavItem_HOME"

    const val ADD_PROPERTY = "EstatiaNavItem_ADD_PROPERTY"

    const val CHATS = "EstatiaNavItem_CHATS"

    const val MARKET = "EstatiaNavItem_MARKET"

    const val PROFILE = "EstatiaNavItem_PROFILE"
}

/**
 * Stable identifiers for screens that represent important Baseline Profile
 * journeys.
 */
internal object ScreenTestTags {

    const val PROPERTY_FEED = "PropertyFeed"

    const val PROPERTY_FEED_PAGE_0 = "PropertyFeedPage_0"

    const val PROPERTY_FEED_PAGE_1 = "PropertyFeedPage_1"

    const val PROPERTY_DETAILS = "PropertyDetailsScreen"

    const val SEARCH_FIELD = "SearchTextField"

    const val TOP_APP_BAR_NAVIGATION = "EstatiaTopAppBarNavIcon"

    const val ADD_PROPERTY = "AddPropertyScreen"

    const val CHATS = "ChatsScreen"

    const val MARKET = "MarketScreen"

    const val PROFILE = "ProfileScreen"

    const val LOGIN_EMAIL_FIELD = "LoginEmailField"

    const val LOGIN_PASSWORD_FIELD = "LoginPasswordField"

    const val LOGIN_BUTTON = "LoginButton"
}

/**
 * Stable identifiers for the actual tabs exposed by ProfileScreen.
 */
private object ProfileTestTags {

    const val LISTINGS = "ProfileTab_LISTINGS"

    const val FAVORITES = "ProfileTab_FAVORITES"

    const val REVIEWS = "ProfileTab_REVIEWS"
}