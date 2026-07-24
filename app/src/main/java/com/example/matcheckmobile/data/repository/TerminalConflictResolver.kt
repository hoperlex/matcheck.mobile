package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.domain.StatusPolicy

/**
 * Авто-разрешение ТЕРМИНАЛЬНЫХ OCC-конфликтов приёмок (delivery) в пользу
 * сервера (server-win) — чинит «приёмка навсегда застряла на Этапе 2».
 *
 * Scope — только delivery. Отгрузки (shipment) не трогаем.
 *
 * Две точки применения (обе — решение и данные ЦЕЛИКОМ внутри транзакции,
 * чтобы не создать сироту при гонке с параллельным push):
 *  - [sweepLocalTerminal] — без сети: снимает conflictPending у строк, чей
 *    ЛОКАЛЬНЫЙ статус уже терминальный (ветка B / накопленное состояние, где
 *    версии равны и reconcile такую строку как staleOnClient не отдаёт).
 *  - [applyReconciled] — по свежему серверному статусу из reconcile-detail
 *    (ветка A: локально ещё filled, сервер уже confirmed_mol).
 *
 * Удаляем ТОЛЬКО conflictPending-мутации; поздние неконфликтные операции
 * (новая редакция, mark_deletion/unmark_deletion) сохраняются и уйдут на
 * портал следующим sync. Отменённый конфликтный payload — осознанная потеря,
 * фиксируется телеметрией (только метаданные) best-effort.
 */
class TerminalConflictResolver(
    private val deliveryDao: RemoteDeliveryDao,
    private val mutationDao: MutationDao,
    private val tx: TransactionRunner,
    private val telemetryDiscard: (List<MutationEntity>) -> Unit = DiscardTelemetry.sentry,
) {

    sealed interface Outcome {
        data object Skipped : Outcome
        data class Applied(val discarded: List<MutationEntity>) : Outcome
    }

    /**
     * Без сети: для каждой conflictPending-приёмки с локальным терминальным
     * статусом — снять конфликт (server-win) внутри транзакции.
     */
    suspend fun sweepLocalTerminal() {
        for (id in deliveryDao.listConflictPendingIds()) {
            val discarded = tx.run {
                val cur = deliveryDao.findById(id) ?: return@run emptyList<MutationEntity>()
                if (!cur.conflictPending || !StatusPolicy.isTerminal(cur.statusCode)) {
                    return@run emptyList<MutationEntity>()
                }
                val removed = deleteConflictMutations(id)
                deliveryDao.upsert(
                    cur.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null),
                )
                removed
            }
            report(discarded)
        }
    }

    /**
     * Решение по свежему серверному dto ВНУТРИ транзакции:
     *  - локально conflictPending + сервер терминальный → server-win: снять
     *    конфликтные мутации и записать серверное состояние ([write]);
     *  - локально conflictPending + сервер ещё нетерминальный → [Outcome.Skipped]
     *    (не перезаписываем свежий конфликт — ждём ручной резолюции);
     *  - иначе — обычная запись серверного состояния.
     *
     * [write] выполняет saveAggregate(dto) — вызывается внутри транзакции.
     */
    suspend fun applyReconciled(
        id: String,
        isServerTerminal: Boolean,
        write: suspend () -> Unit,
    ): Outcome {
        val result = tx.run {
            val cur = deliveryDao.findById(id)
            if (cur?.conflictPending == true) {
                if (!isServerTerminal) return@run Outcome.Skipped
                val removed = deleteConflictMutations(id)
                write()
                Outcome.Applied(removed)
            } else {
                write()
                Outcome.Applied(emptyList())
            }
        }
        if (result is Outcome.Applied) report(result.discarded)
        return result
    }

    private suspend fun deleteConflictMutations(id: String): List<MutationEntity> {
        val conflictMuts = mutationDao.findFor("delivery", id).filter { it.conflictPending }
        conflictMuts.forEach { mutationDao.deleteById(it.id) }
        return conflictMuts
    }

    /** Телеметрия — best-effort, после commit; её сбой не должен влиять на sync. */
    private fun report(discarded: List<MutationEntity>) {
        if (discarded.isNotEmpty()) runCatching { telemetryDiscard(discarded) }
    }
}
