package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
}
