package com.estatia.realestate.apps.benchmark

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Creates a known authenticated application state before benchmark measurement.
 *
 * Production uses a dedicated Firebase account supplied as instrumentation
 * arguments by CI. Demo retains its deterministic demo authentication provider.
 * Passwords are never logged, written to source, or passed through Gradle argv.
 */
internal object BenchmarkAuthentication {
    private const val BENCHMARK_TEST_EMAIL_ARGUMENT = "baselineProfileTestEmail"
    private const val BENCHMARK_TEST_PASSWORD_ARGUMENT = "baselineProfileTestPassword"
    private const val SESSION_RESTORE_TIMEOUT_MS = 20_000L
    private const val LOGIN_SCREEN_TIMEOUT_MS = 30_000L
    private const val AUTHENTICATION_TIMEOUT_MS = 60_000L
    private const val UI_IDLE_TIMEOUT_MS = 5_000L

    fun prepareProductionSession(device: UiDevice) {
        val packageName = BenchmarkTargetPackage.requirePackageName()
        if (packageName.endsWith(".demo")) return

        val arguments = InstrumentationRegistry.getArguments()
        val email = arguments.getString(BENCHMARK_TEST_EMAIL_ARGUMENT).orEmpty()
        val password = arguments.getString(BENCHMARK_TEST_PASSWORD_ARGUMENT).orEmpty()
        check(email.isNotBlank() && password.isNotBlank()) {
            "Production benchmarks require BASELINE_PROFILE_TEST_EMAIL and " +
                "BASELINE_PROFILE_TEST_PASSWORD GitHub Actions secrets."
        }

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = checkNotNull(
            targetContext.packageManager.getLaunchIntentForPackage(packageName),
        ) {
            "Could not resolve the Prod application launch Activity for $packageName."
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        device.pressHome()
        targetContext.startActivity(launchIntent)

        var homeNavigation = device.wait(
            Until.findObject(By.res(NavigationTestTags.HOME)),
            SESSION_RESTORE_TIMEOUT_MS,
        )
        if (homeNavigation == null) {
            val emailField = device.wait(
                Until.findObject(By.res(ScreenTestTags.LOGIN_EMAIL_FIELD)),
                LOGIN_SCREEN_TIMEOUT_MS,
            ) ?: throw AssertionError(
                "Prod exposed neither authenticated Home navigation nor the login email field. " +
                    "Inspect Firebase initialization and app startup diagnostics."
            )
            val passwordField = requireUiObject(
                device = device,
                tag = ScreenTestTags.LOGIN_PASSWORD_FIELD,
                description = "Production benchmark login password field",
            )
            emailField.text = email
            passwordField.text = password

            // UiObject2.setText() updates the fields through accessibility actions.
            // Do not press Back here: the unauthenticated root Activity must stay active.
            device.waitForIdle(UI_IDLE_TIMEOUT_MS)
            requireUiObject(
                device = device,
                tag = ScreenTestTags.LOGIN_BUTTON,
                description = "Production benchmark login button after entering credentials",
            ).click()
            homeNavigation = device.wait(
                Until.findObject(By.res(NavigationTestTags.HOME)),
                AUTHENTICATION_TIMEOUT_MS,
            )
        }

        checkNotNull(homeNavigation) {
            "Firebase benchmark sign-in did not reach authenticated Home. Confirm the account " +
                "has a verified email, completed Estatia's required phone-verification step, " +
                "and has a matching Firestore user document with readable Home property data. " +
                "Credentials are excluded from diagnostics."
        }
        requireUiObject(
            device = device,
            tag = ScreenTestTags.PROPERTY_FEED,
            description = "Home property feed after Firebase benchmark authentication",
            timeoutMs = AUTHENTICATION_TIMEOUT_MS,
        )
        device.waitForIdle(UI_IDLE_TIMEOUT_MS)

        // Keep authentication outside the timed block. The caller launches the
        // persisted session after this force-stop, so frame measurements begin on Home.
        device.executeShellCommand("am force-stop $packageName")
        device.pressHome()
    }

    private fun requireUiObject(
        device: UiDevice,
        tag: String,
        description: String,
        timeoutMs: Long = LOGIN_SCREEN_TIMEOUT_MS,
    ): UiObject2 = checkNotNull(
        device.wait(
            Until.findObject(By.res(tag)),
            timeoutMs,
        ),
    ) {
        "Required authentication UI was not found: $description (resource ID: $tag)."
    }
}
