package com.example.matcheckmobile.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.AuthRepository
import com.example.matcheckmobile.data.repository.LoginError
import com.example.matcheckmobile.data.repository.LoginException
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val deviceSettings: DeviceSettings,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChanged(value: String) {
        _state.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChanged(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        val password = current.password
        if (email.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(error = LoginError.InvalidCredentials) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.login(email, password)
            result.fold(
                onSuccess = { user ->
                    deviceSettings.setCurrentUser(user.id)
                    user.siteId?.let { deviceSettings.setCurrentSite(it) }
                    // Сразу планируем push-then-pull через WorkManager. Worker
                    // переживёт смерть LoginViewModel и работает в фоне.
                    MatcheckSyncScheduler.requestImmediateSync(appContext)
                    MatcheckSyncScheduler.schedulePeriodicSync(appContext)
                    _state.update { it.copy(isSubmitting = false, error = null) }
                    onSuccess()
                },
                onFailure = { throwable ->
                    val error = (throwable as? LoginException)?.error
                        ?: LoginError.Unknown(throwable.message)
                    _state.update { it.copy(isSubmitting = false, error = error) }
                },
            )
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(
                    authRepository = container.authRepository,
                    deviceSettings = container.deviceSettings,
                    appContext = container.appContext,
                ) as T
            }
        }
    }
}

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
)
