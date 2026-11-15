package com.estatia.realestate.apps.core.player_engine.state

import androidx.annotation.OptIn
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import com.estatia.realestate.apps.core.architecture.annotations.Data.PlayerState
import com.estatia.realestate.apps.core.common.concurrency.Confinement
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reducer responsible for managing the logical playback state of a single player.
 * Maps low-level ExoPlayer events to consistent UI-ready states.
 *
 * Includes a watchdog mechanism to prevent hanging in the "Buffering" state
 * when encountering corrupt or zero-duration media.
 */
@Utility
class PlaybackStateReducer(
    private val scope: CoroutineScope,
    private val watchdogTimeoutMs: Long = 7_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Represent the possible UI-visible states of a video player.
     */
    @PlayerState
    sealed interface State {
        data object Idle : State
        data object Buffering : State
        data object Ready : State
        data object Playing : State
        data object Paused : State
        data object Ended : State
        data object Reconnecting : State
        data class Error(val error: PlaybackException) : State
    }

    /**
     * Represents events that trigger state transitions.
     */
    @PlayerState
    sealed interface Event {
        data object Reset : Event
        data object BufferingStarted : Event
        data object BufferingCompleted : Event
        data object Play : Event
        data object Pause : Event
        data object PlaybackEnded : Event
        data object NetworkLost : Event
        data object NetworkRestored : Event
        data class PlaybackError(val error: PlaybackException) : Event
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var watchdogJob: Job? = null

    fun dispatch(event: Event) {
        scope.launch(Dispatchers.Main.immediate) {
            Confinement.checkMainThread()
            // An event that is not valid for the current state is dropped entirely:
            // it neither changes the state nor touches the watchdog.
            val next = reduce(_state.value, event) ?: return@launch
            _state.value = next
            handleWatchdog(event)
        }
    }

    /**
     * Computes the next state, or `null` when [event] is not applicable to [current].
     *
     * - [Event.Pause] only applies while [State.Playing]. ExoPlayer also reports
     *   `isPlaying = false` when it starts rebuffering or reaches the end of the media,
     *   in the same callback as the playback-state change; treating that as a user pause
     *   would overwrite [State.Buffering] / [State.Ended] with [State.Paused].
     * - [Event.NetworkRestored] only applies while [State.Reconnecting]; a connectivity
     *   callback in any other state must not force the player back into buffering.
     */
    private fun reduce(current: State, event: Event): State? {
        return when (event) {
            Event.Reset -> State.Idle
            Event.BufferingStarted -> State.Buffering
            Event.BufferingCompleted -> State.Ready
            Event.Play -> State.Playing
            Event.Pause -> if (current == State.Playing) State.Paused else null
            Event.PlaybackEnded -> State.Ended
            Event.NetworkLost -> State.Reconnecting
            Event.NetworkRestored -> if (current == State.Reconnecting) State.Buffering else null
            is Event.PlaybackError -> State.Error(event.error)
        }
    }

    /**
     * The watchdog guards against hanging in [State.Buffering]. It runs only while buffering,
     * so every event that leaves that state must stop it: in particular [Event.NetworkLost]
     * (the UI is legitimately waiting for connectivity, not stuck) as well as [Event.Play]
     * and [Event.Pause].
     */
    private fun handleWatchdog(event: Event) {
        when (event) {
            Event.BufferingStarted, Event.NetworkRestored -> startWatchdog()
            Event.BufferingCompleted,
            Event.Play,
            Event.Pause,
            Event.NetworkLost,
            Event.PlaybackEnded,
            Event.Reset,
            is Event.PlaybackError -> stopWatchdog()
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = scope.launch {
            delay(watchdogTimeoutMs.milliseconds)
            // If we reached here, it means we've been buffering for too long.
            expireWatchdog()
        }
    }

    /**
     * Raises the synthetic timeout error, but only if the player is still buffering at the
     * moment this runs. The check happens on the main thread, so a state change that landed
     * between the timer firing and this block executing cannot be overwritten by a stale error.
     */
    @OptIn(UnstableApi::class)
    private fun expireWatchdog() {
        scope.launch(Dispatchers.Main.immediate) {
            Confinement.checkMainThread()
            if (_state.value != State.Buffering) return@launch
            watchdogJob = null
            _state.value = State.Error(
                PlaybackException(
                    "Playback attempt timed out (Watchdog)",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
