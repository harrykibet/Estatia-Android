package com.estatia.realestate.apps.core.player_ui.state

/**
 * The single persistent UI-state authority exposed by playback ViewModels.
 *
 * Playback status is transient engine-derived state; mute is a persisted user
 * preference projected from IUserRepository. Keeping both values in one flow
 * gives consumers one coherent snapshot without creating competing StateFlows.
 */
data class VideoPlaybackUiState(
    val playback: PlayerUiState = PlayerUiState.Idle,
    val isMuted: Boolean = false
)
