package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val deviceId: String = "",
    val userId: String = "",
    val siteId: String = "",
    val serverBaseUrl: String = "",
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        container.deviceSettings.deviceIdFlow,
        container.deviceSettings.currentUserIdFlow,
        container.deviceSettings.currentSiteIdFlow,
        container.deviceSettings.serverBaseUrlFlow,
    ) { device, user, site, server ->
        SettingsUiState(device, user, site, server)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setServerBaseUrl(url: String) {
        viewModelScope.launch { container.deviceSettings.setServerBaseUrl(url) }
    }

    /**
     * Выход из аккаунта со сбросом локального состояния.
     *
     * Чистим:
     *  - Room (все справочники, приёмки, фото, очередь мутаций, драфты) —
     *    чтобы под следующим логином не торчали данные предыдущего пользователя;
     *  - syncCursor — иначе следующий /sync запросит дельту со старой границы
     *    и не подсосёт записи, которые «прошли мимо» окна (типовая причина
     *    расхождения наборов УПД между двумя устройствами одного юзера);
     *  - токены через authRepository.logout() — он же эмиттит
     *    SessionEvent.LoggedOut, который перехватывает MatcheckNavHost
     *    и автоматически уводит на LoginScreen.
     *
     * Порядок важен: сначала локальная очистка, потом logout — иначе
     * SessionEvent.LoggedOut может сработать раньше, и наблюдатели
     * (ViewModel'и со scope.launch на Flow Room) увидят пустую БД до
     * того, как UI ушёл на Login.
     */
    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.database.clearAllTables()
            }
            container.deviceSettings.clearSyncCursor()
            container.authRepository.logout()
        }
    }
}
