package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Карантин записей ЧУЖОГО объекта.
 *
 * Сервер отвечает 403 `foreign_site`, когда инспектор пытается изменить
 * приёмку/отгрузку другого объекта. На планшете такое бывает ровно в одном
 * сценарии: в офисе вышли из одного аккаунта и вошли в другой, а в локальной
 * базе остались записи прошлого объекта. Держать их в очереди нельзя (сервер
 * будет отказывать всегда), но и удалять молча тоже нельзя — вместе с записью
 * пропали бы неотправленные снимки. Поэтому:
 *
 *  - все неотправленные фото такой записи получают ТЕРМИНАЛЬНЫЙ статус
 *    [RemotePhotoStatus.QUARANTINED_FOREIGN_SITE] — upload-цикл их больше не
 *    берёт, файл и строка остаются на планшете;
 *  - вся очередь мутаций записи удаляется (иначе она блокирует FIFO);
 *  - сама запись удаляется, только если спасать нечего.
 *
 * Удалить карантин может лишь человек — из «Очереди синхронизации», после
 * экспорта снимков.
 */
class ForeignSiteQuarantine(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val mutationDao: MutationDao,
    private val tx: TransactionRunner,
) {

    fun observeDeliveryPhotos(): Flow<List<RemoteDeliveryPhotoEntity>> =
        deliveryDao.observePhotosByStatus(QUARANTINE_ONLY)

    fun observeShipmentPhotos(): Flow<List<RemoteShipmentPhotoEntity>> =
        shipmentDao.observePhotosByStatus(QUARANTINE_ONLY)

    /**
     * Родители карантинных фото. Partial-reset при смене объекта (Б3) обязан
     * их пропустить: удалив приёмку, он каскадом снёс бы и снимки.
     */
    suspend fun protectedParents(): ProtectedParents = ProtectedParents(
        deliveryIds = deliveryDao.findPhotosByStatus(QUARANTINE_ONLY).map { it.deliveryId }.distinct(),
        shipmentIds = shipmentDao.findPhotosByStatus(QUARANTINE_ONLY).map { it.shipmentId }.distinct(),
    )

    suspend fun quarantineDelivery(deliveryId: String): Outcome {
        val plan = tx.run {
            val photos = deliveryDao.findPhotosByDelivery(deliveryId)
            val unsent = photos.filter { RemotePhotoStatus.isUnsent(it.uploadStatus) }
            val salvageable = unsent.filter { hasLocalFile(it.localBlobPath) }
            mutationDao.deleteFor(ENTITY_DELIVERY, deliveryId)
            if (salvageable.isEmpty()) {
                // Спасать нечего: либо фото нет, либо все уже на сервере, либо
                // локальные blob'ы потеряны. Убираем запись целиком — каскад
                // снесёт items и photos.
                deliveryDao.deleteByIds(listOf(deliveryId))
                Plan(deleted = true, quarantined = 0, files = photos.flatMap(::localPaths))
            } else {
                // Терминальный статус ставим ВСЕМ неотправленным, включая те,
                // у которых blob потерян: иначе они останутся PENDING_UPLOAD и
                // будут вечно получать 403 на каждом синке.
                unsent.forEach {
                    deliveryDao.upsertPhoto(
                        it.copy(
                            uploadStatus = RemotePhotoStatus.QUARANTINED_FOREIGN_SITE,
                            lastUploadError = QUARANTINE_REASON,
                        ),
                    )
                }
                markDeliveryForeign(deliveryId)
                Plan(deleted = false, quarantined = unsent.size, files = emptyList())
            }
        }
        plan.files.forEach(::deleteQuietly)
        return plan.toOutcome()
    }

    suspend fun quarantineShipment(shipmentId: String): Outcome {
        val plan = tx.run {
            val photos = shipmentDao.findPhotosByShipment(shipmentId)
            val unsent = photos.filter { RemotePhotoStatus.isUnsent(it.uploadStatus) }
            val salvageable = unsent.filter { hasLocalFile(it.localBlobPath) }
            mutationDao.deleteFor(ENTITY_SHIPMENT, shipmentId)
            if (salvageable.isEmpty()) {
                shipmentDao.deleteByIds(listOf(shipmentId))
                Plan(deleted = true, quarantined = 0, files = photos.flatMap(::localPaths))
            } else {
                unsent.forEach {
                    shipmentDao.upsertPhoto(
                        it.copy(
                            uploadStatus = RemotePhotoStatus.QUARANTINED_FOREIGN_SITE,
                            lastUploadError = QUARANTINE_REASON,
                        ),
                    )
                }
                markShipmentForeign(shipmentId)
                Plan(deleted = false, quarantined = unsent.size, files = emptyList())
            }
        }
        plan.files.forEach(::deleteQuietly)
        return plan.toOutcome()
    }

    /**
     * Помечает конкретное фото карантинным (403 прилетел на самом presign /
     * confirm, а не на мутации родителя). Родителя не трогает.
     */
    suspend fun quarantineDeliveryPhoto(photo: RemoteDeliveryPhotoEntity) {
        deliveryDao.upsertPhoto(
            photo.copy(
                uploadStatus = RemotePhotoStatus.QUARANTINED_FOREIGN_SITE,
                lastUploadError = QUARANTINE_REASON,
            ),
        )
        markDeliveryForeign(photo.deliveryId)
    }

    suspend fun quarantineShipmentPhoto(photo: RemoteShipmentPhotoEntity) {
        shipmentDao.upsertPhoto(
            photo.copy(
                uploadStatus = RemotePhotoStatus.QUARANTINED_FOREIGN_SITE,
                lastUploadError = QUARANTINE_REASON,
            ),
        )
        markShipmentForeign(photo.shipmentId)
    }

    /** Файлы карантина для экспорта («Сохранить»). Только реально существующие. */
    suspend fun exportableFiles(): List<QuarantinedFile> {
        val deliveries = deliveryDao.findPhotosByStatus(QUARANTINE_ONLY).mapNotNull { p ->
            p.localBlobPath?.let { path ->
                File(path).takeIf { it.exists() }?.let {
                    QuarantinedFile(it, "priyomka_${p.deliveryId.take(8)}_${p.kind}_${p.id.take(8)}.jpg")
                }
            }
        }
        val shipments = shipmentDao.findPhotosByStatus(QUARANTINE_ONLY).mapNotNull { p ->
            p.localBlobPath?.let { path ->
                File(path).takeIf { it.exists() }?.let {
                    QuarantinedFile(it, "otgruzka_${p.shipmentId.take(8)}_${p.kind}_${p.id.take(8)}.jpg")
                }
            }
        }
        return deliveries + shipments
    }

    /**
     * Удаление карантина человеком: сначала снимки и их файлы, затем — только
     * если родитель всё ещё помечен чужим и у него не осталось ни карантинных,
     * ни неотправленных фото, ни мутаций — сама запись.
     */
    suspend fun deleteAll(): Int {
        var removed = 0
        val deliveryPhotos = deliveryDao.findPhotosByStatus(QUARANTINE_ONLY)
        for (parentId in deliveryPhotos.map { it.deliveryId }.distinct()) {
            removed += deleteQuarantinedDelivery(parentId)
        }
        val shipmentPhotos = shipmentDao.findPhotosByStatus(QUARANTINE_ONLY)
        for (parentId in shipmentPhotos.map { it.shipmentId }.distinct()) {
            removed += deleteQuarantinedShipment(parentId)
        }
        return removed
    }

    suspend fun deleteQuarantinedDelivery(deliveryId: String): Int {
        val files = tx.run {
            val photos = deliveryDao.findPhotosByDelivery(deliveryId)
            val quarantined = photos.filter { it.uploadStatus == RemotePhotoStatus.QUARANTINED_FOREIGN_SITE }
            if (quarantined.isEmpty()) return@run emptyList()
            quarantined.forEach { deliveryDao.deletePhotoById(it.id) }
            deleteParentIfEmptyDelivery(deliveryId, photos - quarantined.toSet())
            quarantined.flatMap(::localPaths)
        }
        files.forEach(::deleteQuietly)
        return files.size
    }

    suspend fun deleteQuarantinedShipment(shipmentId: String): Int {
        val files = tx.run {
            val photos = shipmentDao.findPhotosByShipment(shipmentId)
            val quarantined = photos.filter { it.uploadStatus == RemotePhotoStatus.QUARANTINED_FOREIGN_SITE }
            if (quarantined.isEmpty()) return@run emptyList()
            quarantined.forEach { shipmentDao.deletePhotoById(it.id) }
            deleteParentIfEmptyShipment(shipmentId, photos - quarantined.toSet())
            quarantined.flatMap(::localPaths)
        }
        files.forEach(::deleteQuietly)
        return files.size
    }

    private suspend fun deleteParentIfEmptyDelivery(
        deliveryId: String,
        remaining: List<RemoteDeliveryPhotoEntity>,
    ) {
        val parent = deliveryDao.findById(deliveryId) ?: return
        if (parent.lastSyncError != QUARANTINE_REASON) return // запись уже вернулась в свой объект
        if (remaining.any { RemotePhotoStatus.isUnsent(it.uploadStatus) }) return
        if (mutationDao.findFor(ENTITY_DELIVERY, deliveryId).isNotEmpty()) return
        deliveryDao.deleteByIds(listOf(deliveryId))
    }

    private suspend fun deleteParentIfEmptyShipment(
        shipmentId: String,
        remaining: List<RemoteShipmentPhotoEntity>,
    ) {
        val parent = shipmentDao.findById(shipmentId) ?: return
        if (parent.lastSyncError != QUARANTINE_REASON) return
        if (remaining.any { RemotePhotoStatus.isUnsent(it.uploadStatus) }) return
        if (mutationDao.findFor(ENTITY_SHIPMENT, shipmentId).isNotEmpty()) return
        shipmentDao.deleteByIds(listOf(shipmentId))
    }

    private suspend fun markDeliveryForeign(deliveryId: String) {
        val parent = deliveryDao.findById(deliveryId) ?: return
        if (parent.lastSyncError == QUARANTINE_REASON && !parent.conflictPending) return
        deliveryDao.upsert(
            parent.copy(
                conflictPending = false,
                serverSnapshotJson = null,
                lastSyncError = QUARANTINE_REASON,
            ),
        )
    }

    private suspend fun markShipmentForeign(shipmentId: String) {
        val parent = shipmentDao.findById(shipmentId) ?: return
        if (parent.lastSyncError == QUARANTINE_REASON && !parent.conflictPending) return
        shipmentDao.upsert(
            parent.copy(
                conflictPending = false,
                serverSnapshotJson = null,
                lastSyncError = QUARANTINE_REASON,
            ),
        )
    }

    private fun hasLocalFile(path: String?): Boolean =
        !path.isNullOrEmpty() && runCatching { File(path).exists() }.getOrDefault(false)

    private fun localPaths(photo: RemoteDeliveryPhotoEntity): List<String> =
        listOfNotNull(photo.localBlobPath, photo.localThumbPath)

    private fun localPaths(photo: RemoteShipmentPhotoEntity): List<String> =
        listOfNotNull(photo.localBlobPath, photo.localThumbPath)

    /** Файловые операции откатить нельзя — делаем их только после коммита. */
    private fun deleteQuietly(path: String) {
        runCatching { File(path).delete() }
    }

    private data class Plan(
        val deleted: Boolean,
        val quarantined: Int,
        val files: List<String>,
    ) {
        fun toOutcome(): Outcome =
            if (deleted) Outcome.Deleted else Outcome.Quarantined(quarantined)
    }

    sealed interface Outcome {
        /** Спасать было нечего — запись и её очередь удалены с планшета. */
        data object Deleted : Outcome
        /** Запись оставлена как контейнер для [count] снимков в карантине. */
        data class Quarantined(val count: Int) : Outcome
    }

    data class ProtectedParents(
        val deliveryIds: List<String>,
        val shipmentIds: List<String>,
    ) {
        val isEmpty: Boolean get() = deliveryIds.isEmpty() && shipmentIds.isEmpty()
    }

    data class QuarantinedFile(val file: File, val displayName: String)

    companion object {
        const val ENTITY_DELIVERY = "delivery"
        const val ENTITY_SHIPMENT = "shipment"

        /**
         * Один и тот же текст в `lastUploadError` фото и в `lastSyncError`
         * родителя — по нему UI показывает карантин, а partial-reset и
         * ручное удаление понимают, что запись «чужая».
         */
        const val QUARANTINE_REASON = "foreign_site"

        private val QUARANTINE_ONLY = listOf(RemotePhotoStatus.QUARANTINED_FOREIGN_SITE)
    }
}
