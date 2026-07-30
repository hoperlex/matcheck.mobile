package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.domain.StatusPolicy

/**
 * Авто-разрешение ТЕРМИНАЛЬНЫХ OCC-конфликтов в пользу сервера (server-win) —
 * чинит «запись навсегда застряла на Этапе 2».
 *
 * Работает и для приёмок, и для отгрузок. Раньше было только для приёмок, и у
 * отгрузок конфликт версий замирал до ручного разрешения: инспектор подтвердил
 * выезд с портала, а планшет с непроталкнутой правкой держал его на 2 Этапе.
 * Терминальный статус у обеих сущностей один и тот же — `confirmed_mol`
 * (см. [StatusPolicy]), поэтому логика буквально одна, различаются только DAO.
 *
 * Scope (жёсткий): только конфликтные **upsert**-мутации. Конфликтные
 * `mark_deletion`/`unmark_deletion`/будущие операции НЕ трогаем — они остаются
 * замороженными для обычной резолюции; флаг entity снимаем лишь если после
 * удаления upsert-конфликтов других конфликтов не осталось.
 *
 * Две точки применения (обе — решение и данные ЦЕЛИКОМ внутри транзакции,
 * чтобы не создать сироту при гонке с параллельным push):
 *  - [sweepLocalTerminal] — без сети: снимает конфликт у записей с ЛОКАЛЬНЫМ
 *    терминальным статусом. Перебирает ОБЪЕДИНЕНИЕ id с флагом entity и id из
 *    `mutationDao.listConflicts()` — ловит «сироту» (конфликтная мутация висит,
 *    а флаг entity уже снят более поздней мутацией).
 *  - [applyReconciled] — по свежему серверному статусу из reconcile-detail
 *    (ветка A: локально ещё filled/shipped, сервер уже confirmed_mol).
 *
 * Отменённый конфликтный upsert-payload — осознанная потеря (Вариант A),
 * фиксируется телеметрией (только метаданные) best-effort.
 */
class TerminalConflictResolver(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val mutationDao: MutationDao,
    private val tx: TransactionRunner,
    private val telemetryDiscard: (List<MutationEntity>) -> Unit = DiscardTelemetry.sentry,
) {

    sealed interface Outcome {
        data object Skipped : Outcome
        data class Applied(val discarded: List<MutationEntity>) : Outcome
    }

    /** Локальное состояние записи, нужное алгоритму. Общее для обеих сущностей. */
    private data class Local(val statusCode: String, val conflictPending: Boolean)

    /**
     * Доступ к одной из двух сущностей. Вынесено, чтобы алгоритм существовал в
     * единственном экземпляре: дублирование неминуемо разъехалось бы, как уже
     * разъехалось однажды — отгрузки просто забыли.
     */
    private inner class Ops(
        val type: String,
        val conflictPendingIds: suspend () -> List<String>,
        val load: suspend (String) -> Local?,
        val setConflict: suspend (String, Boolean) -> Unit,
    )

    private val deliveryOps = Ops(
        type = ENTITY_DELIVERY,
        conflictPendingIds = { deliveryDao.listConflictPendingIds() },
        load = { id -> deliveryDao.findById(id)?.let { Local(it.statusCode, it.conflictPending) } },
        setConflict = { id, value ->
            deliveryDao.findById(id)?.let { row ->
                deliveryDao.upsert(
                    if (value) row.copy(conflictPending = true)
                    else row.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null),
                )
            }
        },
    )

    private val shipmentOps = Ops(
        type = ENTITY_SHIPMENT,
        conflictPendingIds = { shipmentDao.listConflictPendingIds() },
        load = { id -> shipmentDao.findById(id)?.let { Local(it.statusCode, it.conflictPending) } },
        setConflict = { id, value ->
            shipmentDao.findById(id)?.let { row ->
                shipmentDao.upsert(
                    if (value) row.copy(conflictPending = true)
                    else row.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null),
                )
            }
        },
    )

    private fun opsFor(type: String): Ops =
        if (type == ENTITY_SHIPMENT) shipmentOps else deliveryOps

    /**
     * Без сети: для каждой записи, у которой есть конфликт (по флагу entity ИЛИ
     * по conflictPending-мутации) и ЛОКАЛЬНЫЙ статус терминальный — server-win
     * внутри транзакции. Проходит по обеим сущностям.
     */
    suspend fun sweepLocalTerminal() {
        for (ops in listOf(deliveryOps, shipmentOps)) {
            val ids = (
                ops.conflictPendingIds() +
                    mutationDao.listConflicts().filter { it.entityType == ops.type }.map { it.entityId }
                ).distinct()
            for (id in ids) {
                val discarded = tx.run {
                    val cur = ops.load(id) ?: return@run emptyList<MutationEntity>()
                    if (!StatusPolicy.isTerminal(cur.statusCode)) return@run emptyList<MutationEntity>()
                    serverWinUpserts(ops, id, cur)
                }
                report(discarded)
            }
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
        entityType: String = ENTITY_DELIVERY,
        write: suspend () -> Unit,
    ): Outcome {
        val ops = opsFor(entityType)
        val result = tx.run {
            val cur = ops.load(id)
            if (cur?.conflictPending == true) {
                if (!isServerTerminal) return@run Outcome.Skipped
                val all = mutationDao.findFor(ops.type, id)
                val upsertConflicts = all.filter { it.conflictPending && it.operation == "upsert" }
                upsertConflicts.forEach { mutationDao.deleteById(it.id) }
                write() // saveAggregate(dto) → confirmed_mol, conflictPending=false (toEntity default)
                // Если остались другие конфликты (не-upsert) — write() уже снял флаг,
                // возвращаем его, чтобы они не потерялись для ручной резолюции.
                if (all.any { it.conflictPending && it.operation != "upsert" }) {
                    ops.setConflict(id, true)
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
     * Удаляет ТОЛЬКО конфликтные upsert-мутации записи; снимает флаг entity лишь
     * если других conflictPending-мутаций не осталось. Возвращает удалённые
     * мутации (для телеметрии).
     */
    private suspend fun serverWinUpserts(ops: Ops, id: String, cur: Local): List<MutationEntity> {
        val all = mutationDao.findFor(ops.type, id)
        val upsertConflicts = all.filter { it.conflictPending && it.operation == "upsert" }
        if (upsertConflicts.isEmpty() && !cur.conflictPending) return emptyList() // чистить нечего
        upsertConflicts.forEach { mutationDao.deleteById(it.id) }
        val hasOtherConflicts = all.any { it.conflictPending && it.operation != "upsert" }
        if (cur.conflictPending && !hasOtherConflicts) ops.setConflict(id, false)
        return upsertConflicts
    }

    /** Телеметрия — best-effort, после commit; её сбой не должен влиять на sync. */
    private fun report(discarded: List<MutationEntity>) {
        if (discarded.isNotEmpty()) runCatching { telemetryDiscard(discarded) }
    }

    companion object {
        const val ENTITY_DELIVERY = "delivery"
        const val ENTITY_SHIPMENT = "shipment"
    }
}
