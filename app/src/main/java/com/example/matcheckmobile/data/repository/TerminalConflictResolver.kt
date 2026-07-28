package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.domain.StatusPolicy

/**
 * Авто-разрешение ТЕРМИНАЛЬНЫХ OCC-конфликтов приёмок (delivery) в пользу
 * сервера (server-win) — чинит «приёмка навсегда застряла на Этапе 2».
 *
 * Scope (жёсткий): только delivery и только конфликтные **upsert**-мутации.
 * Конфликтные `mark_deletion`/`unmark_deletion`/будущие операции НЕ трогаем —
 * они остаются замороженными для обычной резолюции; флаг entity снимаем лишь
 * если после удаления upsert-конфликтов других конфликтов не осталось.
 *
 * Две точки применения (обе — решение и данные ЦЕЛИКОМ внутри транзакции,
 * чтобы не создать сироту при гонке с параллельным push):
 *  - [sweepLocalTerminal] — без сети: снимает конфликт у приёмок с ЛОКАЛЬНЫМ
 *    терминальным статусом. Перебирает ОБЪЕДИНЕНИЕ id с флагом entity и id из
 *    `mutationDao.listConflicts()` — ловит «сироту» (конфликтная мутация висит,
 *    а флаг entity уже снят более поздней мутацией).
 *  - [applyReconciled] — по свежему серверному статусу из reconcile-detail
 *    (ветка A: локально ещё filled, сервер уже confirmed_mol).
 *
 * Отменённый конфликтный upsert-payload — осознанная потеря (Вариант A),
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
     * Без сети: для каждой приёмки, у которой есть конфликт (по флагу entity ИЛИ
     * по conflictPending-мутации) и ЛОКАЛЬНЫЙ статус терминальный — server-win
     * внутри транзакции.
     */
    suspend fun sweepLocalTerminal() {
        val ids = (
            deliveryDao.listConflictPendingIds() +
                mutationDao.listConflicts().filter { it.entityType == "delivery" }.map { it.entityId }
            ).distinct()
        for (id in ids) {
            val discarded = tx.run {
                val cur = deliveryDao.findById(id) ?: return@run emptyList<MutationEntity>()
                if (!StatusPolicy.isTerminal(cur.statusCode)) return@run emptyList<MutationEntity>()
                serverWinUpserts(id, cur)
            }
            report(discarded)
        }
    }

    /**
     * Решение по свежему серверному dto ВНУТРИ транзакции:
     *  - локально conflictPending + сервер терминальный → server-win над
     *    конфликтными upsert-мутациями + записать серверное состояние ([write]);
     *  - локально conflictPending + сервер ещё нетерминальный → [Outcome.Skipped];
     *  - иначе — обычная запись серверного состояния.
     *
     * [write] выполняет saveAggregate(dto) внутри транзакции.
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
                val all = mutationDao.findFor("delivery", id)
                val upsertConflicts = all.filter { it.conflictPending && it.operation == "upsert" }
                upsertConflicts.forEach { mutationDao.deleteById(it.id) }
                write() // saveAggregate(dto) → confirmed_mol, conflictPending=false (toEntity default)
                // Если остались другие конфликты (не-upsert) — write() уже снял флаг,
                // возвращаем его, чтобы они не потерялись для ручной резолюции.
                if (all.any { it.conflictPending && it.operation != "upsert" }) {
                    deliveryDao.findById(id)?.let { deliveryDao.upsert(it.copy(conflictPending = true)) }
                }
                Outcome.Applied(upsertConflicts)
            } else {
                write()
                Outcome.Applied(emptyList())
            }
        }
        if (result is Outcome.Applied) report(result.discarded)
        return result
    }

    /**
     * Удаляет ТОЛЬКО конфликтные upsert-мутации приёмки; снимает флаг entity лишь
     * если других conflictPending-мутаций не осталось. Возвращает удалённые
     * мутации (для телеметрии).
     */
    private suspend fun serverWinUpserts(id: String, cur: RemoteDeliveryEntity): List<MutationEntity> {
        val all = mutationDao.findFor("delivery", id)
        val upsertConflicts = all.filter { it.conflictPending && it.operation == "upsert" }
        if (upsertConflicts.isEmpty() && !cur.conflictPending) return emptyList() // чистить нечего
        upsertConflicts.forEach { mutationDao.deleteById(it.id) }
        val hasOtherConflicts = all.any { it.conflictPending && it.operation != "upsert" }
        if (cur.conflictPending && !hasOtherConflicts) {
            deliveryDao.upsert(cur.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null))
        }
        return upsertConflicts
    }

    /** Телеметрия — best-effort, после commit; её сбой не должен влиять на sync. */
    private fun report(discarded: List<MutationEntity>) {
        if (discarded.isNotEmpty()) runCatching { telemetryDiscard(discarded) }
    }
}
