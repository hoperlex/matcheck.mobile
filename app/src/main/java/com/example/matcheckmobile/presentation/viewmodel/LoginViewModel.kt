package com.example.matcheckmobile.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.auth.RememberedCredentialsStore
import com.example.matcheckmobile.data.remote.sse.SseConnectionManager
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
    private val sseConnectionManager: SseConnectionManager,
    private val rememberedCredentials: RememberedCredentialsStore,
    private val appContext: Context,
) : ViewModel() {

    // Автозаполнение: после logout подставляем последний успешный логин+пароль,
    // чтобы инспектору не вбивать всё заново при повторном входе.
    private val _state = MutableStateFlow(
        LoginUiState(
            email = rememberedCredentials.email.orEmpty(),
            password = rememberedCredentials.password.orEmpty(),
        ),
    )
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
                    // Запоминаем удачные креды для автозаполнения после logout.
                    rememberedCredentials.save(email, password)
                    deviceSettings.setCurrentUser(user.id)
                    user.siteId?.let { deviceSettings.setCurrentSite(it) }
                    // Сразу планируем push-then-pull через WorkManager. Worker
                    // переживёт смерть LoginViewModel и работает в фоне.
                    MatcheckSyncScheduler.requestImmediateSync(appContext)
                    MatcheckSyncScheduler.schedulePeriodicSync(appContext)
                    // Запускаем SSE — на ближайшее серверное событие триггерим sync.
                    sseConnectionManager.start()
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
                    sseConnectionManager = container.sseConnectionManager,
                    rememberedCredentials = container.rememberedCredentialsStore,
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
