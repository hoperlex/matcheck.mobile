package com.example.matcheckmobile.presentation.viewmodel

import android.content.Context
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Состояние жеста «потянуть для обновления»: индикатор и текст ошибки. */
data class SyncRefreshState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Жест «потянуть для обновления» — одна механика на все списки.
 *
 * Зачем общий делегат, а не копия в каждом ViewModel. Экранов, которым он
 * нужен, шесть: два архивных и четыре рабочих (выбор УПД и 2 Этап, в обоих
 * контурах — приёмки и выезда). Копия в каждом разъехалась бы, как уже
 * разъезжались зеркальные пути в этом проекте, а цена расхождения здесь —
 * инспектор, которому один экран говорит «обновлено», а другой молчит.
 *
 * Почему жест ждёт результат, а не гаснет по факту постановки задачи. Индикатор
 * обязан гаснуть по факту синхронизации: иначе при мёртвой сети он мигнёт и
 * оставит инспектора с прежним списком, уверенного, что данные свежие. Ровно
 * этого «тихого ничего» и нужно избегать — на объекте у него нет другого
 * способа узнать, что планшет отстал.
 *
 * Судим по исходу цикла, а не по статусу задачи: логическая ошибка синка
 * возвращается технически успешной задачей (FAILED-звено увело бы в FAILED все
 * приложенные за ним запросы — см. MatcheckSyncWorker), поэтому по `state`
 * «синхронизировались» от «сервер отказал» не отличить.
 */
class SyncRefreshDelegate(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SyncRefreshState())
    val state: StateFlow<SyncRefreshState> = _state.asStateFlow()

    fun refresh() {
        // Повторный свайп во время идущего обновления игнорируем: он поставил бы
        // ещё одно звено в цепочку, а коалесцирование по поколению всё равно
        // выполнило бы его вхолостую.
        if (_state.value.isRefreshing) return
        _state.value = SyncRefreshState(isRefreshing = true)
        scope.launch {
            val result = runCatching {
                MatcheckSyncScheduler.requestImmediateSyncAndAwait(appContext)
            }.getOrNull()
            _state.value = SyncRefreshState(
                isRefreshing = false,
                // null означает истёкший таймаут ожидания (без сети задача
                // остаётся ENQUEUED), ok=false — сервер ответил отказом. Для
                // инспектора это один и тот же исход: данные не обновились.
                error = if (result?.ok == true) null else SYNC_FAILED_MESSAGE,
            )
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        const val SYNC_FAILED_MESSAGE = "Не удалось обновить — проверьте связь"
    }
}
