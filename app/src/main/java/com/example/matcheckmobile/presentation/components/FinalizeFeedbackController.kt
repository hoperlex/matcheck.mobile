package com.example.matcheckmobile.presentation.components

import com.example.matcheckmobile.monitoring.IncidentRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Владелец success-оверлея «Завершить Этап». Живёт в AppContainer, а не в
 * композиции экрана: раньше оверлей принадлежал форме и уходил вместе с ней,
 * поэтому сбой в цепочке «показать → подождать → popBackStack» запирал
 * инспектора на финализированной форме навсегда.
 *
 * Ключевой момент — отсчёт НЕ живёт в LaunchedEffect и не зависит от
 * Main-диспетчера. Само исчезновение диалога всё равно случится на ближайшей
 * рекомпозиции Main, но это уже неважно: навигация к тому моменту выполнена.
 */
class FinalizeFeedbackController(
    private val scope: CoroutineScope,
    private val recorder: IncidentRecorder,
) {
    data class Shown(val eventId: Long, val stage: String)

    private val _state = MutableStateFlow<Shown?>(null)
    val state: StateFlow<Shown?> = _state.asStateFlow()

    /**
     * Поколение показа. Одной лишь отмены hideJob мало: корутина могла уже
     * пройти delay и вот-вот выполнить присваивание — отмена не прерывает код
     * между точками приостановки. Поэтому таймер гасит оверлей только если
     * поколение всё ещё его.
     */
    private val generation = AtomicLong(0)
    private var hideJob: Job? = null

    /** @return eventId показа — им связываются записи журнала. */
    fun show(stage: String, holdMs: Long = HOLD_MS): Long {
        val id = generation.incrementAndGet()
        hideJob?.cancel()
        _state.value = Shown(id, stage)
        hideJob = scope.launch {
            delay(holdMs)
            if (generation.get() == id) {
                _state.value = null
                recorder.record("overlay_hidden", "eventId" to id, "reason" to "timer")
            }
        }
        return id
    }

    /** Аварийный выход: тап по галочке, тап мимо неё, системный Back. */
    fun hide(reason: String = "user") {
        val prev = _state.value ?: return
        generation.incrementAndGet() // обесценивает любой живой таймер
        hideJob?.cancel()
        _state.value = null
        recorder.record("overlay_hidden", "eventId" to prev.eventId, "reason" to reason)
    }

    companion object {
        /** Столько же, сколько было в старом delay внутри форм. */
        const val HOLD_MS = 900L
    }
}
