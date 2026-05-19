package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Список приёмок, ожидающих 2 Этап (статус `filled` — «Оформлена»).
 * Подтверждение МОЛ переводит их в `confirmed_mol`, после чего они уходят
 * из этого списка.
 */
class Stage2ListViewModel(container: AppContainer) : ViewModel() {

    val deliveries: StateFlow<List<RemoteDeliveryEntity>> = container
        .deliveryRepository
        .observeByStatus("filled")
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}
