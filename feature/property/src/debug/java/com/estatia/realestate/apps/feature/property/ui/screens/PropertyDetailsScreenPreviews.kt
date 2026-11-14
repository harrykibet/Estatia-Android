package com.estatia.realestate.apps.feature.property.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.estatia.realestate.apps.core.designsystem.component.EstatiaBackground
import com.estatia.realestate.apps.core.designsystem.theme.EstatiaTheme
import com.estatia.realestate.apps.core.testing.fixtures.PropertyFixtures
import com.estatia.realestate.apps.core.ui.DevicePreviews
import com.estatia.realestate.apps.core.player_ui.state.PlayerUiState
import com.estatia.realestate.apps.feature.property.ui.management.viewmodels.PropertyDetailsUiState

@Preview(name = "Property Details - Success", showBackground = true)
@DevicePreviews
@Composable
fun PropertyDetailsScreenSuccessPreview() {
    EstatiaTheme {
        EstatiaBackground {
            PropertyDetailsScreen(
                uiState = PropertyDetailsUiState.Success(PropertyFixtures.single()),
                onBackClick = {},
                playbackUiState = PlayerUiState.Idle,
                isMuted = false,
                onMuteToggle = {},
                onPlaybackRetry = {},
                getPlayer = { _, _, _, _ -> throw Exception("Not implemented") },
                onPausePlayback = {},
                isMediaActive = { false }
            )
        }
    }
}

@Preview(name = "Property Details - Success (Swahili)", showBackground = true, locale = "sw")
@Composable
fun PropertyDetailsScreenSwahiliPreview() {
    EstatiaTheme {
        EstatiaBackground {
            PropertyDetailsScreen(
                uiState = PropertyDetailsUiState.Success(PropertyFixtures.single()),
                onBackClick = {},
                playbackUiState = PlayerUiState.Idle,
                isMuted = false,
                onMuteToggle = {},
                onPlaybackRetry = {},
                getPlayer = { _, _, _, _ -> throw Exception("Not implemented") },
                onPausePlayback = {},
                isMediaActive = { false }
            )
        }
    }
}

@Preview(name = "Property Details - Loading", showBackground = true)
@DevicePreviews
@Composable
fun PropertyDetailsScreenLoadingPreview() {
    EstatiaTheme {
        EstatiaBackground {
            PropertyDetailsScreen(
                uiState = PropertyDetailsUiState.Loading,
                onBackClick = {},
                playbackUiState = PlayerUiState.Idle,
                isMuted = false,
                onMuteToggle = {},
                onPlaybackRetry = {},
                getPlayer = { _, _, _, _ -> throw Exception("Not implemented") },
                onPausePlayback = {},
                isMediaActive = { false }
            )
        }
    }
}

@Preview(name = "Property Details - Error", showBackground = true)
@DevicePreviews
@Composable
fun PropertyDetailsScreenErrorPreview() {
    EstatiaTheme {
        EstatiaBackground {
            PropertyDetailsScreen(
                uiState = PropertyDetailsUiState.Error("Failed to load property details. Please try again."),
                onBackClick = {},
                playbackUiState = PlayerUiState.Idle,
                isMuted = false,
                onMuteToggle = {},
                onPlaybackRetry = {},
                getPlayer = { _, _, _, _ -> throw Exception("Not implemented") },
                onPausePlayback = {},
                isMediaActive = { false }
            )
        }
    }
}
