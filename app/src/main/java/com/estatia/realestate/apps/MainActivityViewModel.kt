package com.estatia.realestate.apps

import com.estatia.realestate.apps.core.architecture.annotations.Identity.ViewModelMarker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estatia.realestate.apps.core.domain.security.IAuthRepository
import com.estatia.realestate.apps.core.domain.config.IConfigLifecycle
import com.estatia.realestate.apps.core.domain.repository.IUserRepository
import com.estatia.realestate.apps.core.model.user.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.estatia.realestate.apps.core.architecture.annotations.Identity.Contract

/**
 * High-level ViewModel for the primary application Activity.
 *
 * 🏗️ OPERATIONAL CONTRACT:
 * - Responsibility: Manage global application state, including authentication and configuration readiness.
 * - Concurrency: Thread-safe reactive state combination via [combine].
 * - Resilience: Surfaces a loading state while core infrastructure (Remote Config) initializes.
 * - Lifecycle: Collects source state for the lifetime of this Activity-scoped ViewModel.
 */
@ViewModelMarker
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    userDataRepository: IUserRepository,
    authRepository: IAuthRepository,
    config: IConfigLifecycle
) : ViewModel() {

    /*
     * This is the ViewModel's only persistent UI-state authority. PiP mode and the
     * authentication/configuration state are updated atomically in the same value.
     */
    private val _uiState = MutableStateFlow<MainActivityUiState>(MainActivityUiState.Loading())
    val uiState: StateFlow<MainActivityUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                userDataRepository.userData,
                authRepository.isUserAuthenticated(),
                config.isReady
            ) { userData, isAuthenticated, isConfigReady ->
                if (isConfigReady) {
                    MainActivityUiState.Success(userData, isAuthenticated)
                } else {
                    MainActivityUiState.Loading()
                }
            }.collect { applicationState ->
                _uiState.update { currentState ->
                    applicationState.withPiPMode(currentState.isInPiPMode)
                }
            }
        }
    }

    @Contract
    sealed interface MainActivityUiState {
        val isInPiPMode: Boolean

        data class Loading(
            override val isInPiPMode: Boolean = false
        ) : MainActivityUiState

        data class Success(
            val userData: UserData,
            val isAuthenticated: Boolean,
            override val isInPiPMode: Boolean = false
        ) : MainActivityUiState

        /**
         * Returns `true` if the state wasn't loaded yet and it should keep showing the splash screen.
         */
        fun shouldKeepSplashScreen() = this is Loading

        fun withPiPMode(inPiP: Boolean): MainActivityUiState = when (this) {
            is Loading -> copy(isInPiPMode = inPiP)
            is Success -> copy(isInPiPMode = inPiP)
        }
    }

    fun updatePiPMode(inPiP: Boolean) {
        _uiState.update { currentState ->
            currentState.withPiPMode(inPiP)
        }
    }
}
