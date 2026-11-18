package com.estatia.realestate.apps.core.intelligence

import android.content.Context
import com.estatia.realestate.apps.core.common.exceptions.getOrThrow
import com.estatia.realestate.apps.core.common.interfaces.IClock
import com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
import com.estatia.realestate.apps.core.model.engagement.SafetyResult
import com.estatia.realestate.apps.core.testing.chaos.input.ChaosInputGenerator
import com.estatia.realestate.apps.core.testing.chaos.input.InputBehavior
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MlKitContentSafetyServiceTest {

    private lateinit var service: MlKitContentSafetyService
    private val context: Context = mockk(relaxed = true)
    private val clock = IClock { 1_000L }

    @Before
    fun setup() {
        val metricsTracker = mockk<IMetricsTracker>(relaxed = true)
        service = MlKitContentSafetyService(context, metricsTracker, clock)
    }

    @Test
    fun `validateText flags abusive content based on heuristic patterns`() = runTest {
        val toxicText = "This is a very abusive_term1 message."
        val result = service.validateText(toxicText).getOrThrow()

        assertTrue("Abusive content should be flagged", result is SafetyResult.Flagged)
    }

    @Test
    fun `detectSensitiveData identifies phone numbers and emails`() = runTest {
        val text = "Call me at +254712345678 or email test@estatia.com"
        val entities = service.detectSensitiveData(text).getOrThrow()

        assertTrue("Should detect phone number", entities.any { it.type == "PHONE" })
        assertTrue("Should detect email", entities.any { it.type == "EMAIL" })
    }

    @Test
    fun `validateText handles empty input safely using chaos generator`() = runTest {
        val emptyInput = ChaosInputGenerator.generateString(InputBehavior.EmptyInput)!!
        val result = service.validateText(emptyInput).getOrThrow()

        assertTrue("Empty text should be safe (or handled via validation)", result is SafetyResult.Safe)
    }
}
