package com.estatia.realestate.apps.core.player_engine.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.player_engine.di.EngineScope
import com.estatia.realestate.apps.core.player_engine.di.PlayerDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.estatia.realestate.apps.core.architecture.annotations.Identity.Manager

/**
 * Handles Android audio focus requests and transitions.
 */
// Justification: Required for low-level Media3 playback orchestration.
@UnstableApi
@Singleton
@Manager
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:EngineScope private val engineScope: CoroutineScope,
    @param:PlayerDispatcher private val playerDispatcher: CoroutineDispatcher
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null
    
    private var onFocusLost: ((permanent: Boolean) -> Unit)? = null
    private var onFocusGained: (() -> Unit)? = null
    private var onDuck: (() -> Unit)? = null
    private var onUnduck: (() -> Unit)? = null

    /** What an Android focus-change code means for playback. */
    internal enum class FocusSignal { LOST_PERMANENT, LOST_TRANSIENT, DUCK, GAINED, IGNORE }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                engineScope.launch(playerDispatcher) {
                    // Unplugged headphones: pause, but never auto-resume.
                    onFocusLost?.invoke(true)
                }
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        engineScope.launch(playerDispatcher) {
            when (classify(focusChange)) {
                FocusSignal.LOST_PERMANENT -> {
                    // Another app took focus for good. Drop our request so the next play()
                    // asks again instead of believing it still holds focus.
                    abandon()
                    onFocusLost?.invoke(true)
                }
                FocusSignal.LOST_TRANSIENT -> onFocusLost?.invoke(false)
                FocusSignal.DUCK -> onDuck?.invoke()
                FocusSignal.GAINED -> {
                    onUnduck?.invoke()
                    onFocusGained?.invoke()
                }
                FocusSignal.IGNORE -> Unit
            }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(noisyReceiver, filter)
        }
    }

    /**
     * @param onLost called with `permanent = true` when focus is lost for good (or headphones
     * were unplugged): playback must pause and stay paused. `false` means a transient loss that
     * [onGained] will undo.
     * @param onDuck focus was lost but the system allows playing quietly.
     * @param onUnduck volume should return to normal (focus regained).
     */
    fun setCallbacks(
        onLost: (permanent: Boolean) -> Unit,
        onGained: () -> Unit,
        onDuck: () -> Unit = {},
        onUnduck: () -> Unit = {}
    ) {
        this.onFocusLost = onLost
        this.onFocusGained = onGained
        this.onDuck = onDuck
        this.onUnduck = onUnduck
    }

    fun request(): Boolean {
        // Idempotency check: don't churn requests if we already have an active one
        if (activeFocusRequest != null) return true

        val audioManager = audioManager ?: return false

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()

        // Only remember the request if the system accepted it (granted or delayed). Keeping a
        // failed request would make every later request() a no-op and playback would never
        // regain focus.
        return when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                activeFocusRequest = request
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                activeFocusRequest = request
                false
            }
            else -> false
        }
    }

    fun abandon() {
        activeFocusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
        }
        activeFocusRequest = null
    }

    internal companion object {
        fun classify(focusChange: Int): FocusSignal = when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> FocusSignal.LOST_PERMANENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusSignal.LOST_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusSignal.DUCK
            AudioManager.AUDIOFOCUS_GAIN -> FocusSignal.GAINED
            else -> FocusSignal.IGNORE
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (_: Exception) { }
    }
}
