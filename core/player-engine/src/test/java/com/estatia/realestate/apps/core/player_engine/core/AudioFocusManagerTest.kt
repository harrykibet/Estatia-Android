package com.estatia.realestate.apps.core.player_engine.core

import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.player_engine.core.AudioFocusManager.Companion.classify
import com.estatia.realestate.apps.core.player_engine.core.AudioFocusManager.FocusSignal
import org.junit.Assert.assertEquals
import org.junit.Test

// Justification: Required for testing Media3 playback components.
@UnstableApi
class AudioFocusManagerTest {

    @Test
    fun `permanent loss is distinguished from transient loss`() {
        assertEquals(FocusSignal.LOST_PERMANENT, classify(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(FocusSignal.LOST_TRANSIENT, classify(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test
    fun `can-duck loss ducks instead of pausing`() {
        assertEquals(FocusSignal.DUCK, classify(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
    }

    @Test
    fun `gain and unknown codes`() {
        assertEquals(FocusSignal.GAINED, classify(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(FocusSignal.IGNORE, classify(Int.MIN_VALUE))
    }
}
