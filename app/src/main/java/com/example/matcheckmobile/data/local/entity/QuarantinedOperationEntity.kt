package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Операция, которую сервер отверг по групповому протоколу «одна машина — одна
 * приёмка» (409 `group_claimed` / `group_changed` / `group_incomplete` /
 * `source_documents_not_ready`).
 *
 * Повторять такую операцию бессмысленно, а заморозить её в очереди — значит
 * спрятать карточку машины (локальная приёмка держит документы «привязанными»)
 * и оставить инспектора без объяснения. Поэтому операция целиком переезжает
 * сюда: полный снимок того, что ушло бы на сервер, и КОПИИ всех её фото в
 * собственном каталоге (`filesDir/group_quarantine/<id>/`). Файлы принадлежат
 * записи карантина — ни загрузка, ни очистки их не трогают.
 *
 * Удаляет запись только человек — из «Очереди синхронизации».
 *
 * Колонки для списка вынесены отдельно от [snapshotJson], чтобы экран не
 * разбирал JSON на каждую перерисовку.
 */
@Entity(tableName = "quarantined_operations")
data class QuarantinedOperationEntity(
    @PrimaryKey val id: String,
    /** Пока только `delivery`: групповой протокол действует для приёмки. */
    val entityType: String,
    /** Локальный id операции, которую сервер отверг. */
    val entityId: String,
    /** Код отказа сервера: `group_claimed`, `group_changed`, … */
    val reason: String,
    /** Когда машину занял другой планшет (только `group_claimed`), ISO-8601. */
    val claimedAt: String?,
    val siteId: String?,
    /** Машина, по которой была открыта форма. */
    val groupId: String?,
    /** Документы операции — для заголовка карточки в «Очереди». */
    val sourceDocumentIdsJson: String,
    val vehiclePlate: String?,
    /** Статус операции на момент отказа: `filled` или `confirmed_mol` (оба этапа). */
    val statusCode: String,
    val photoCount: Int,
    /** Полный снимок: тело запроса upsert, тип машины и список фото. */
    val snapshotJson: String,
    /**
     * Когда по снимку создан новый черновик 1 Этапа («Оформить заново»).
     * Запись после этого остаётся: в ней данные и фото 2 Этапа.
     */
    val reissuedAt: Long?,
    val createdAt: Long,
)
