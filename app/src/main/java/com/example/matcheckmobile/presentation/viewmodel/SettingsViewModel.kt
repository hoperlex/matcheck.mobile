package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.auth.PendingWork
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val deviceId: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val siteId: String = "",
    val serverBaseUrl: String = "",
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        container.deviceSettings.deviceIdFlow,
        container.deviceSettings.currentUserIdFlow,
        container.deviceSettings.currentSiteIdFlow,
        container.deviceSettings.serverBaseUrlFlow,
        container.tokenStorage.state,
    ) { device, user, site, server, token ->
        SettingsUiState(
            deviceId = device,
            userId = user,
            userEmail = token.userEmail.orEmpty(),
            siteId = site,
            serverBaseUrl = server,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /**
     * «Горизонтальный режим» — пользовательский тумблер на главной. Отдельный
     * StateFlow, чтобы не раздувать SettingsUiState combine — флаг используется
     * только парой MainScreen (UI) ↔ MainActivity (применение requestedOrientation).
     */
    val prefersLandscape: StateFlow<Boolean> = container.deviceSettings
        .prefersLandscapeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun setPrefersLandscape(value: Boolean) {
        viewModelScope.launch { container.deviceSettings.setPrefersLandscape(value) }
    }

    fun setServerBaseUrl(url: String) {
        viewModelScope.launch { container.deviceSettings.setServerBaseUrl(url) }
    }

    /**
     * Неотправленное на планшете — для предупреждения перед выходом.
     * null, пока не проверяли.
     */
    private val _pendingBeforeLogout =
        MutableStateFlow<PendingWork?>(null)
    val pendingBeforeLogout: StateFlow<PendingWork?> =
        _pendingBeforeLogout.asStateFlow()

    /** Очистка при выходе не удалась — показываем и НЕ рвём сессию. */
    private val _logoutError = MutableStateFlow<String?>(null)
    val logoutError: StateFlow<String?> = _logoutError.asStateFlow()

    fun consumeLogoutError() {
        _logoutError.value = null
    }

    /**
     * Запрос на выход. Если на планшете осталась неотправленная работа —
     * сначала показываем, что именно пропадёт, и ждём подтверждения.
     * Требование «без потерь данных»: «Выйти» больше не стирает молча.
     *
     * @return true, если выход выполнен сразу (очередь пуста).
     */
    fun requestLogout(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val pending = runCatching { container.accountSwitchCoordinator.pendingWork() }.getOrNull()
            if (pending != null && !pending.isEmpty) {
                _pendingBeforeLogout.value = pending
                return@launch
            }
            performLogout()
            onDone()
        }
    }

    fun cancelLogout() {
        _pendingBeforeLogout.value = null
    }

    /** «Отправить и выйти»: пуш под текущим токеном, затем повторная проверка. */
    fun syncBeforeLogout() {
        viewModelScope.launch {
            runCatching { container.syncRepository.syncOnce() }
            val pending = runCatching { container.accountSwitchCoordinator.pendingWork() }.getOrNull()
            _pendingBeforeLogout.value = pending?.takeIf { !it.isEmpty }
            if (_pendingBeforeLogout.value == null) performLogout()
        }
    }

    fun confirmLogoutDiscardingData() {
        viewModelScope.launch {
            _pendingBeforeLogout.value = null
            performLogout()
        }
    }

    /**
     * Выход со сбросом локального состояния.
     *
     * Чистим через общую процедуру [AccountSwitchCoordinator.wipeAccountData]
     * (та же, что при смене аккаунта): гасим SSE, снимаем sync-задачи, берём
     * sync-мьютекс и только затем чистим Room, курсор и каталоги с фото.
     * Раньше logout делал голый `clearAllTables()` — воркер мог дописать в
     * уже очищенную базу, а jpeg'и прошлого инспектора оставались на диске.
     *
     * Порядок важен: сначала локальная очистка, потом logout — иначе
     * SessionEvent.LoggedOut может сработать раньше, и наблюдатели
     * (ViewModel'и со scope.launch на Flow Room) увидят пустую БД до
     * того, как UI ушёл на Login.
     */
    private suspend fun performLogout() {
        val outcome = runCatching { container.accountSwitchCoordinator.wipeAccountData() }
        val done = outcome.getOrNull()?.isComplete == true
        if (!done) {
            // Fail-closed: сессию не рвём. Иначе данные остались бы на диске,
            // а токенов, под которыми их можно отправить, уже нет — и никто бы
            // об этом не узнал (раньше ошибка просто глоталась).
            _logoutError.value = outcome.exceptionOrNull()?.let {
                "Не удалось очистить данные: ${it.message ?: it::class.simpleName}"
            } ?: "Очистка прошла не полностью — часть файлов не удалилась. Попробуйте ещё раз."
            return
        }
        container.authRepository.logout()
    }

    /** Совместимость со старыми точками вызова. */
    fun logout() = requestLogout()
}
