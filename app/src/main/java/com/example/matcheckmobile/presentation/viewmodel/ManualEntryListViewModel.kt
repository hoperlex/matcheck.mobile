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

/** Одна строка в списке незавершённых ручных вносов. */
data class ManualEntryDraftRow(
    val id: String,
    val titleText: String,
    val subtitleText: String,
    val createdAt: Long,
)

/**
 * Список незавершённых черновиков «Ручной внос» для текущего объекта.
 * Аналог [Stage2ListViewModel], но источник — локальная таблица
 * `manual_entry_drafts` (черновики не синкаются, живут до «Завершить»).
 * Фильтр по объекту — fail-closed: пустой `effectiveSiteId` → пустой список.
 */
class ManualEntryListViewModel(private val container: AppContainer) : ViewModel() {

    val rows: StateFlow<List<ManualEntryDraftRow>> = combine(
        container.manualEntryDraftRepository.observeAll(),
        container.tokenStorage.state,
    ) { drafts, tokenSnapshot ->
        val currentSiteId = tokenSnapshot.effectiveSiteId
        if (currentSiteId.isNullOrBlank()) return@combine emptyList()
        drafts
            .filter { it.siteId == currentSiteId }
            .map { entity ->
                val state = container.manualEntryDraftRepository.toState(entity)
                val photos = state.documentPhotoPaths.size + state.cargoPhotoPaths.size
                ManualEntryDraftRow(
                    id = entity.localDraftId,
                    titleText = state.manualUpdText.trim().ifBlank { "Ручной внос" },
                    subtitleText = "$photos фото · ${state.materials.size} материалов",
                    createdAt = entity.createdAt,
                )
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * Эйджер-создание черновика: вставляет пустую строку (она сразу видна в
     * списке) и отдаёт её id в навигацию. No-op без привязки к объекту.
     */
    fun createDraft(onCreated: (String) -> Unit) {
        val site = container.tokenStorage.state.value.effectiveSiteId
        if (site.isNullOrBlank()) return
        viewModelScope.launch {
            val id = container.manualEntryDraftRepository.create(site)
            onCreated(id)
        }
    }

    /** Удаление черновика по свайпу: сносит и локальные temp-фото, чтобы не копить orphan'ы. */
    fun delete(id: String) {
        viewModelScope.launch {
            container.manualEntryDraftRepository.findById(id)?.let { entity ->
                val state = container.manualEntryDraftRepository.toState(entity)
                (state.documentPhotoPaths + state.cargoPhotoPaths).forEach { path ->
                    runCatching { File(path).delete() }
                }
            }
            container.manualEntryDraftRepository.deleteById(id)
        }
    }
}
