package com.example.matcheckmobile.data.auth

import android.util.Log
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.repository.AttachmentRepository
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.domain.model.RemotePhotoStatus

/**
 * Неотправленное на планшете — то, что безвозвратно пропадёт при очистке
 * данных аккаунта (`clearAllTables()` + удаление каталогов с фото).
 */
data class PendingWork(
    /** Очереди на отправку: новые мутации и остатки legacy-пайплайна. */
    val mutations: Int,
    /** Снимки, которые ещё можно доставить обычной синхронизацией. */
    val photos: Int,
    val drafts: Int,
    /**
     * Карантин чужого объекта. Отдельно: синхронизация его не вылечит
     * (сервер отвечает 403 всегда), решается только руками в «Очереди».
     */
    val quarantinedPhotos: Int,
    /** Хотя бы один подсчёт упал — трактуем как «данные могут быть». */
    val probeFailed: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !probeFailed &&
            mutations == 0 && photos == 0 && drafts == 0 && quarantinedPhotos == 0

    /** Можно ли надеяться, что синхронизация очистит очередь. */
    val syncCanHelp: Boolean get() = mutations > 0 || photos > 0 || probeFailed

    /** Человекочитаемое перечисление для диалога подтверждения. */
    fun describe(): String = buildList {
        if (mutations > 0) add("записей в очереди: $mutations")
        if (photos > 0) add("незагруженных фото: $photos")
        if (drafts > 0) add("черновиков: $drafts")
        if (quarantinedPhotos > 0) {
            add("фото чужого объекта: $quarantinedPhotos (только вручную, в «Очереди синхронизации»)")
        }
        if (probeFailed) add("проверить очередь не удалось — возможны неотправленные данные")
    }.joinToString(", ").ifEmpty { "нет данных о состоянии очереди" }
}

/**
 * Считает [PendingWork] по локальной базе.
 *
 * **Считаем не все таблицы.** `clearAllTables()` сносит и обычный серверный
 * snapshot (приёмки, отгрузки, УПД, справочники), но он восстанавливается
 * ре-синком, и его наличие не должно вечно запрещать выход. Классификация:
 *
 * | Категория | В барьере |
 * |---|---|
 * | server snapshot: remote_deliveries/shipments/source_documents, справочники | нет |
 * | локальные черновики: stage1/2, shipment stage1/2, ручной внос и отгрузка | да |
 * | очереди: mutations, sync_queue, receipt_sessions, material_operations | да |
 * | файлы: remote_*_photos не UPLOADED, operation_attachments | да |
 * | карантин QUARANTINED_FOREIGN_SITE | да, отдельной строкой |
 *
 * Держать рядом со списком сущностей в `MatcheckDatabase`: новая таблица не
 * должна появиться мимо барьера.
 *
 * Все проверки **fail-closed** — ошибка запроса даёт не ноль, а
 * [PendingWork.probeFailed]. Молча удалить данные из-за упавшего запроса
 * недопустимо.
 */
class PendingWorkProbe(private val database: MatcheckDatabase) {

    suspend fun probe(): PendingWork {
        var failed = false
        suspend fun count(what: String, block: suspend () -> Int): Int =
            runCatching { block() }.getOrElse { e ->
                Log.w(TAG, "не удалось посчитать $what", e)
                failed = true
                0
            }

        val deliveryDao = database.remoteDeliveryDao()
        val shipmentDao = database.remoteShipmentDao()
        val mutationDao = database.mutationDao()

        val queues = count("mutations") {
            mutationDao.listPending(Long.MAX_VALUE).size + mutationDao.listConflicts().size
        } +
            count("sync_queue") { database.syncQueueDao().count() } +
            count("receipt_sessions") {
                database.receiptSessionDao().countBySyncStatuses(OperationRepository.UNSYNCED)
            } +
            count("material_operations") {
                database.materialOperationDao().countFreeBySyncStatuses(OperationRepository.UNSYNCED)
            }

        val photos = count("photos") {
            deliveryDao.findPhotosByStatus(UNSENT_PHOTO_STATUSES).size +
                shipmentDao.findPhotosByStatus(UNSENT_PHOTO_STATUSES).size
        } + count("operation_attachments") {
            database.operationAttachmentDao().countByStatuses(AttachmentRepository.PENDING_STATUSES)
        }

        val drafts = count("drafts") {
            database.stage1DraftDao().count() +
                database.stage2DraftDao().count() +
                database.shipmentStage1DraftDao().count() +
                database.shipmentStage2DraftDao().count() +
                database.manualEntryDraftDao().count() +
                database.manualDispatchDraftDao().count()
        }

        val quarantined = count("quarantine") {
            deliveryDao.findPhotosByStatus(QUARANTINE_ONLY).size +
                shipmentDao.findPhotosByStatus(QUARANTINE_ONLY).size
        }

        return PendingWork(
            mutations = queues,
            photos = photos,
            drafts = drafts,
            quarantinedPhotos = quarantined,
            probeFailed = failed,
        )
    }

    private companion object {
        const val TAG = "PendingWorkProbe"
        // Включает и стадию подготовки (PENDING_PREPARE / PREPARING /
        // PREPARE_ERROR): кадр уже снят, но ещё не собран — выходить из
        // аккаунта с ним так же опасно, как с неотправленным blob'ом.
        val UNSENT_PHOTO_STATUSES = RemotePhotoStatus.UNSENT
        val QUARANTINE_ONLY = listOf(RemotePhotoStatus.QUARANTINED_FOREIGN_SITE)
    }
}
