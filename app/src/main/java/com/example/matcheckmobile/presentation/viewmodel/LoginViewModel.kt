package com.example.matcheckmobile.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.auth.AccountSwitchCoordinator
import com.example.matcheckmobile.data.auth.RememberedCredentialsStore
import com.example.matcheckmobile.data.remote.sse.SseConnectionManager
import com.example.matcheckmobile.data.repository.AuthRepository
import com.example.matcheckmobile.data.repository.LoginError
import com.example.matcheckmobile.data.repository.LoginException
import com.example.matcheckmobile.data.repository.SyncRepository
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
    private val accountSwitchCoordinator: AccountSwitchCoordinator,
    private val syncRepository: SyncRepository,
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

    /**
     * Вход. Перед сменой аккаунта проверяем, не осталось ли на планшете
     * неотправленной работы прошлого инспектора: приёмок в очереди, недогруженных
     * фото, черновиков. Если осталась — вход НЕ выполняется, пока человек не
     * выберет, что с ней делать (синхронизировать или удалить). Требование
     * «без потерь данных» отменяет прежнее «чистим молча».
     */
    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        val password = current.password
        if (email.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(error = LoginError.InvalidCredentials) }
            return
        }
        viewModelScope.launch {
            if (!current.wipeConfirmed && isAccountSwitch(email)) {
                val pending = runCatching { accountSwitchCoordinator.pendingWork() }.getOrNull()
                if (pending != null && !pending.isEmpty) {
                    _state.update { it.copy(pendingSwitch = pending, error = null) }
                    return@launch
                }
            }
            performLogin(email, password, current.wipeConfirmed, onSuccess)
        }
    }

    /**
     * Вводят другой email, чем в текущей сессии → это смена аккаунта.
     * Сравнение по email, а не по userId: id нового пользователя известен
     * только после ответа сервера, а спросить надо ДО отправки логина.
     * Барьер в [AccountSwitchCoordinator.prepareForLogin] всё равно проверит
     * настоящий id — эта проверка лишь про то, когда показать диалог.
     */
    private fun isAccountSwitch(email: String): Boolean {
        val previous = authRepository.session.value.userEmail?.trim().orEmpty()
        return previous.isNotEmpty() && !previous.equals(email, ignoreCase = true)
    }

    /** «Синхронизировать»: пуш под СТАРЫМ токеном, вход — только если очередь опустела. */
    fun syncBeforeSwitch() {
        if (_state.value.isSyncingBeforeSwitch) return
        _state.update { it.copy(isSyncingBeforeSwitch = true, switchError = null) }
        viewModelScope.launch {
            runCatching { syncRepository.syncOnce() }
            val pending = runCatching { accountSwitchCoordinator.pendingWork() }.getOrNull()
            _state.update {
                when {
                    pending == null -> it.copy(
                        isSyncingBeforeSwitch = false,
                        switchError = "Не удалось проверить очередь",
                    )
                    pending.isEmpty -> it.copy(
                        isSyncingBeforeSwitch = false,
                        pendingSwitch = null,
                        switchError = null,
                    )
                    else -> it.copy(
                        isSyncingBeforeSwitch = false,
                        pendingSwitch = pending,
                        switchError = "Отправить не удалось, осталось: ${pending.describe()}",
                    )
                }
            }
        }
    }

    /** «Удалить и войти»: явное согласие потерять неотправленное. */
    fun confirmWipeAndLogin(onSuccess: () -> Unit) {
        _state.update { it.copy(wipeConfirmed = true, pendingSwitch = null, switchError = null) }
        submit(onSuccess)
    }

    fun cancelAccountSwitch() {
        _state.update { it.copy(pendingSwitch = null, switchError = null, isSyncingBeforeSwitch = false) }
    }

    private suspend fun performLogin(
        email: String,
        password: String,
        confirmedWipe: Boolean,
        onSuccess: () -> Unit,
    ) {
        _state.update { it.copy(isSubmitting = true, error = null) }
        run {
            // onBeforeActivate — до сохранения токенов: если на планшете лежат
            // данные ДРУГОГО пользователя, они стираются, иначе фоновый sync
            // отправил бы их уже под новой сессией и записи уехали бы на чужой
            // объект (см. AccountSwitchCoordinator).
            val result = authRepository.login(email, password) { user ->
                accountSwitchCoordinator.prepareForLogin(user.id, confirmedWipe = confirmedWipe)
            }
            result.fold(
                onSuccess = { user ->
                    // Запоминаем удачные креды для автозаполнения после logout.
                    rememberedCredentials.save(email, password)
                    deviceSettings.setCurrentUser(user.id)
                    // siteId == null (инспектор без объекта) НЕ оставляет
                    // предыдущее значение: иначе штамп фото и фильтры списков
                    // продолжили бы показывать объект прошлого аккаунта.
                    deviceSettings.setCurrentSite(user.siteId.orEmpty())
                    // Сразу планируем push-then-pull через WorkManager. Worker
                    // переживёт смерть LoginViewModel и работает в фоне.
                    MatcheckSyncScheduler.requestImmediateSync(appContext)
                    MatcheckSyncScheduler.schedulePeriodicSync(appContext)
                    // Запускаем SSE — на ближайшее серверное событие триггерим sync.
                    sseConnectionManager.start()
                    _state.update {
                        it.copy(isSubmitting = false, error = null, wipeConfirmed = false)
                    }
                    onSuccess()
                },
                onFailure = { throwable ->
                    val error = (throwable as? LoginException)?.error
                        ?: LoginError.Unknown(throwable.message)
                    // Согласие на удаление действует ровно на одну попытку:
                    // после неудачи оно не должно молча сработать позже.
                    _state.update { it.copy(isSubmitting = false, error = error, wipeConfirmed = false) }
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
                    accountSwitchCoordinator = container.accountSwitchCoordinator,
                    syncRepository = container.syncRepository,
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
    /**
     * Непусто → показываем диалог «на планшете осталась работа прошлого
     * аккаунта». Вход не выполняется, пока человек не выберет вариант.
     */
    val pendingSwitch: AccountSwitchCoordinator.PendingWork? = null,
    val isSyncingBeforeSwitch: Boolean = false,
    val switchError: String? = null,
    /** Человек согласился удалить неотправленное. Живёт одну попытку входа. */
    val wipeConfirmed: Boolean = false,
)
