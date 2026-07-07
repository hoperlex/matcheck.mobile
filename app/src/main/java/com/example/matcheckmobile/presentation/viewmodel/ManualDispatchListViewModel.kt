package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Одна строка в списке незавершённых ручных выносов. */
data class ManualDispatchDraftRow(
    val id: String,
    val titleText: String,
    val subtitleText: String,
    val createdAt: Long,
)

/**
 * Список незавершённых черновиков «Ручной вынос» для текущего объекта
 * (зеркало [ManualEntryListViewModel] для shipment'ов).
 */
class ManualDispatchListViewModel(private val container: AppContainer) : ViewModel() {

    val rows: StateFlow<List<ManualDispatchDraftRow>> = combine(
        container.manualDispatchDraftRepository.observeAll(),
        container.tokenStorage.state,
    ) { drafts, tokenSnapshot ->
        val currentSiteId = tokenSnapshot.effectiveSiteId
        if (currentSiteId.isNullOrBlank()) return@combine emptyList()
        drafts
            .filter { it.siteId == currentSiteId }
            .map { entity ->
                val state = container.manualDispatchDraftRepository.toState(entity)
                val photos = state.documentPhotoPaths.size + state.cargoPhotoPaths.size
                ManualDispatchDraftRow(
                    id = entity.localDraftId,
                    titleText = state.manualUpdText.trim().ifBlank { "Ручной вынос" },
                    subtitleText = "$photos фото · ${state.materials.size} материалов",
                    createdAt = entity.createdAt,
                )
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun createDraft(onCreated: (String) -> Unit) {
        val site = container.tokenStorage.state.value.effectiveSiteId
        if (site.isNullOrBlank()) return
        viewModelScope.launch {
            val id = container.manualDispatchDraftRepository.create(site)
            onCreated(id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            container.manualDispatchDraftRepository.findById(id)?.let { entity ->
                val state = container.manualDispatchDraftRepository.toState(entity)
                (state.documentPhotoPaths + state.cargoPhotoPaths).forEach { path ->
                    runCatching { File(path).delete() }
                }
            }
            container.manualDispatchDraftRepository.deleteById(id)
        }
    }
}
