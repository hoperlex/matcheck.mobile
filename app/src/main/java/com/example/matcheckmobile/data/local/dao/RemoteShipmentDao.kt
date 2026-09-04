package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteShipmentDao {

    @Upsert
    suspend fun upsert(entity: RemoteShipmentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RemoteShipmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceItems(items: List<RemoteShipmentItemEntity>)

    @Query("DELETE FROM remote_shipment_items WHERE shipmentId = :shipmentId")
    suspend fun deleteItemsByShipment(shipmentId: String)

    @Query("DELETE FROM remote_shipment_photos WHERE shipmentId = :shipmentId")
    suspend fun deletePhotosByShipment(shipmentId: String)

    @Query("DELETE FROM remote_shipment_photos WHERE uploadStatus = :status AND lastUploadError = :error")
    suspend fun deletePhotosByStatusAndError(status: String, error: String): Int

    @androidx.room.Upsert
    suspend fun upsertPhoto(photo: RemoteShipmentPhotoEntity)

    /** См. RemoteDeliveryDao.insertPhotoIntents — IGNORE, не откатываем продвинувшиеся строки. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhotoIntents(photos: List<RemoteShipmentPhotoEntity>)

    /** См. RemoteDeliveryDao.claimPhotoForPrepare. */
    @Query(
        """
        UPDATE remote_shipment_photos
        SET uploadStatus = 'PREPARING', preparingSince = :now
        WHERE id = :id AND uploadStatus IN ('PENDING_PREPARE', 'PREPARE_ERROR')
        """,
    )
    suspend fun claimPhotoForPrepare(id: String, now: Long): Int

    /** См. RemoteDeliveryDao.releaseExpiredPreparing. */
    @Query(
        """
        UPDATE remote_shipment_photos
        SET uploadStatus = 'PENDING_PREPARE', preparingSince = NULL
        WHERE uploadStatus = 'PREPARING'
          AND (preparingSince IS NULL OR preparingSince < :expiredBefore)
        """,
    )
    suspend fun releaseExpiredPreparing(expiredBefore: Long): Int

    /** См. RemoteDeliveryDao.countAwaitingPrepareForSource. */
    @Query(
        """
        SELECT COUNT(*) FROM remote_shipment_photos
        WHERE sourcePath = :sourcePath
          AND uploadStatus IN ('PENDING_PREPARE', 'PREPARING', 'PREPARE_ERROR')
        """,
    )
    suspend fun countAwaitingPrepareForSource(sourcePath: String): Int

    @Query("SELECT * FROM remote_shipment_photos WHERE uploadStatus IN (:statuses)")
    suspend fun findPhotosByStatus(statuses: List<String>): List<RemoteShipmentPhotoEntity>

    // Симметрично RemoteDeliveryDao.resetStuckUploadingPhotos — сброс зависших
    // в UPLOADING фото (процесс убит mid-upload), чтобы они повторились.
    @Query("UPDATE remote_shipment_photos SET uploadStatus = 'PENDING_UPLOAD' WHERE uploadStatus = 'UPLOADING'")
    suspend fun resetStuckUploadingPhotos(): Int

    @Query("SELECT * FROM remote_shipment_photos WHERE uploadStatus IN (:statuses) ORDER BY takenAt DESC")
    fun observePhotosByStatus(statuses: List<String>): Flow<List<RemoteShipmentPhotoEntity>>

    @Query("SELECT COUNT(*) FROM remote_shipment_photos WHERE uploadStatus IN (:statuses)")
    fun observePhotoCountByStatus(statuses: List<String>): Flow<Int>

    @Query("SELECT * FROM remote_shipment_photos WHERE id = :id")
    suspend fun findPhotoById(id: String): RemoteShipmentPhotoEntity?

    @Query("DELETE FROM remote_shipment_photos WHERE id = :id")
    suspend fun deletePhotoById(id: String)


    /**
     * Наблюдение за фото одной операции. Формы 2 Этапа и архива обязаны быть
     * реактивными: пока экран открыт, PhotoPrepareWorker успевает удалить
     * sourcePath, сделать thumb и сменить клиентский id на серверный. Со
     * снимком, снятым один раз при загрузке формы, UI остался бы с путями,
     * которых на диске уже нет, и показал бы «фото недоступно».
     */
    @Query("SELECT * FROM remote_shipment_photos WHERE shipmentId = :shipmentId ORDER BY takenAt")
    fun observePhotosByShipment(shipmentId: String): Flow<List<RemoteShipmentPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhotoIfAbsent(photo: RemoteShipmentPhotoEntity)

    /**
     * Обновляет ТОЛЬКО серверные колонки. Локальные — localBlobPath,
     * localThumbPath, sourcePath, idempotencyKey, contentType, uploadStatus,
     * lastUploadError, preparingSince — здесь не перечислены и потому
     * сохраняются по построению.
     *
     * COALESCE, а не присваивание: серверный DTO может не знать того, что
     * знаем мы (contentHash считается локально при подготовке). Пустое
     * серверное поле не должно затирать заполненное локальное — только
     * дополнять.
     */
    @Query(
        """
        UPDATE remote_shipment_photos
        SET s3Key = COALESCE(:s3Key, s3Key),
            thumbS3Key = COALESCE(:thumbS3Key, thumbS3Key),
            contentHash = COALESCE(:contentHash, contentHash),
            uploadedAt = COALESCE(:uploadedAt, uploadedAt),
            takenAt = :takenAt,
            kind = :kind,
            stage = :stage
        WHERE id = :id
        """,
    )
    suspend fun updateServerPhotoColumns(
        id: String,
        s3Key: String?,
        thumbS3Key: String?,
        contentHash: String?,
        uploadedAt: String?,
        takenAt: String,
        kind: String,
        stage: String,
    )

    @Query("UPDATE remote_shipment_photos SET uploadStatus = 'UPLOADED', lastUploadError = NULL WHERE id = :id")
    suspend fun markPhotoUploaded(id: String)

    /**
     * Мердж строки, приехавшей с сервера, поверх локальной.
     *
     * Нельзя писать серверный DTO целиком: RemoteMappers.toEntity ставит
     * localBlobPath/localThumbPath = null, и обычный upsert стирал бы указатели
     * на файлы, которые лежат на диске. Именно из-за этого открытие 2 Этапа
     * само загоняло показ фото в S3.
     *
     * Статус с сервера НЕ применяем: если локально идёт работа (PENDING_PREPARE
     * с живым sourcePath), сервер о ней не знает, и понижение статуса отправило
     * бы кадр в UPLOAD_ERROR «blob missing» с последующим удалением строки.
     * Исключение — heal: локально отправлять уже нечего, а сервер подтверждает
     * загрузку.
     */
    @Transaction
    suspend fun upsertServerPhoto(photo: RemoteShipmentPhotoEntity) {
        val existing = findPhotoById(photo.id)
        if (existing == null) {
            // uploadedAt = null у серверной строки означает presign без confirm:
            // объекта в S3 нет, локального blob'а тоже. Заводить такую строку —
            // значит создать uploadable-запись без файла, которая гарантированно
            // уедет в UPLOAD_ERROR «blob missing» и будет вычищена.
            if (photo.uploadedAt == null) return
            insertPhotoIfAbsent(photo)
            return
        }
        updateServerPhotoColumns(
            id = photo.id,
            s3Key = photo.s3Key,
            thumbS3Key = photo.thumbS3Key,
            contentHash = photo.contentHash,
            uploadedAt = photo.uploadedAt,
            takenAt = photo.takenAt,
            kind = photo.kind,
            stage = photo.stage,
        )
        val nothingLeftToSend = existing.localBlobPath == null && existing.sourcePath == null
        if (photo.uploadedAt != null && nothingLeftToSend &&
            existing.uploadStatus != RemotePhotoStatus.UPLOADED
        ) {
            markPhotoUploaded(photo.id)
        }
    }

    /**
     * Смена клиентского PK на серверный одной транзакцией.
     *
     * Раньше шли подряд deletePhotoById + upsertPhoto: смерть процесса между
     * ними теряла строку целиком вместе с сохранённой локальной миниатюрой.
     */
    @Transaction
    suspend fun replaceUploadedPhotoId(oldId: String, row: RemoteShipmentPhotoEntity) {
        if (oldId != row.id) deletePhotoById(oldId)
        upsertPhoto(row)
    }


    /**
     * Сохранённые миниатюры уже отправленных фото — кандидаты на вытеснение,
     * когда каталог перерос лимит. Только UPLOADED: у неотправленных кадров
     * миниатюра ещё нужна pipeline'у загрузки.
     */
    @Query("SELECT id, localThumbPath FROM remote_shipment_photos WHERE uploadStatus = 'UPLOADED' AND localThumbPath IS NOT NULL")
    suspend fun findUploadedLocalThumbs(): List<LocalThumbRef>

    /** После вытеснения файла путь обязан исчезнуть: иначе UI сошлётся на несуществующий файл. */
    @Query("UPDATE remote_shipment_photos SET localThumbPath = NULL WHERE id = :id")
    suspend fun clearLocalThumb(id: String)

    @Transaction
    suspend fun saveAggregate(
        shipment: RemoteShipmentEntity,
        items: List<RemoteShipmentItemEntity>,
        photos: List<RemoteShipmentPhotoEntity>,
        /** См. RemoteDeliveryDao.saveAggregate — кадры этой же транзакции, IGNORE. */
        photoIntents: List<RemoteShipmentPhotoEntity> = emptyList(),
    ) {
        upsert(shipment)
        deleteItemsByShipment(shipment.id)
        if (items.isNotEmpty()) replaceItems(items)
        if (photoIntents.isNotEmpty()) insertPhotoIntents(photoIntents)
        // Photo живут своим pipeline и сервер на upsert их не возвращает —
        // если делать deletePhotos+replace, локальные PENDING_UPLOAD стираются
        // сразу после push мутации (см. комментарий в RemoteDeliveryDao).
        for (p in photos) upsertServerPhoto(p)
    }

    @Query("SELECT * FROM remote_shipments WHERE id = :id")
    suspend fun findById(id: String): RemoteShipmentEntity?

    @Query(
        """
        SELECT * FROM remote_shipments
        WHERE pendingDeletionAt IS NULL
        ORDER BY shippedAt DESC, createdAt DESC
        """
    )
    fun observeActive(): Flow<List<RemoteShipmentEntity>>

    @Query(
        """
        SELECT * FROM remote_shipments
        WHERE pendingDeletionAt IS NOT NULL
        ORDER BY pendingDeletionAt DESC
        """
    )
    fun observeTrash(): Flow<List<RemoteShipmentEntity>>

    @Query("DELETE FROM remote_shipments WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Полная очистка серверного snapshot — для smart-reset при смене
     * user.siteId в админке. items/photos удалятся каскадом по FK с
     * onDelete=CASCADE. См. SiteChangeReset.resumeIfNeeded.
     */
    @Query("DELETE FROM remote_shipments")
    suspend fun deleteAll()

    /** См. RemoteDeliveryDao.deleteAllExcept — зеркальная частичная очистка. */
    @Query("DELETE FROM remote_shipments WHERE id NOT IN (:keepIds)")
    suspend fun deleteAllExcept(keepIds: List<String>): Int

    /** id отгрузок, у которых есть хотя бы одно фото, не доехавшее на сервер. */
    @Query("SELECT DISTINCT shipmentId FROM remote_shipment_photos WHERE uploadStatus <> 'UPLOADED'")
    suspend fun findParentIdsWithUnsentPhotos(): List<String>

    @Query("SELECT id FROM remote_shipments WHERE conflictPending = 1")
    suspend fun listConflictPendingIds(): List<String>

    // Лёгкая проекция (id, version) всех локальных отгрузок — для reconcile-сверки
    // (read-only). См. SyncRepository.reconcileOnce.
    @Query("SELECT id, version FROM remote_shipments")
    suspend fun listReconcileVersions(): List<ReconcileVersionRow>

    @Query("SELECT * FROM remote_shipments WHERE conflictPending = 1 ORDER BY updatedAt DESC")
    fun observeConflicts(): kotlinx.coroutines.flow.Flow<List<RemoteShipmentEntity>>

    @Query(
        """
        SELECT sourceDocumentIdsJson FROM remote_shipments
        WHERE pendingDeletionAt IS NULL AND sourceDocumentIdsJson != '[]'
        """
    )
    fun observeAttachedSourceDocumentIdsJson(): kotlinx.coroutines.flow.Flow<List<String>>

    @Query(
        """
        SELECT * FROM remote_shipments
        WHERE statusCode IN (:statuses) AND pendingDeletionAt IS NULL
        ORDER BY shippedAt DESC, createdAt DESC
        """
    )
    fun observeByStatuses(statuses: List<String>): kotlinx.coroutines.flow.Flow<List<RemoteShipmentEntity>>

    /**
     * Активная (не помеченная на удаление) отгрузка по natural key
     * siteId + statusCode + sourceDocumentIdsJson. Используется в
     * [ShipmentRepository.upsert] для дедупликации при повторных
     * finalizeStage1 после ошибки фото / process kill / double-tap.
     * См. симметричный [RemoteDeliveryDao.findByNaturalKey].
     */
    @Query(
        """
        SELECT * FROM remote_shipments
        WHERE siteId = :siteId
            AND statusCode = :statusCode
            AND sourceDocumentIdsJson = :sourceDocumentIdsJson
            AND pendingDeletionAt IS NULL
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun findByNaturalKey(
        siteId: String,
        statusCode: String,
        sourceDocumentIdsJson: String,
    ): RemoteShipmentEntity?

    @Query("SELECT * FROM remote_shipment_items WHERE shipmentId = :shipmentId ORDER BY lineNo ASC")
    suspend fun findItemsByShipment(shipmentId: String): List<RemoteShipmentItemEntity>

    @Query("SELECT * FROM remote_shipment_photos WHERE shipmentId = :shipmentId ORDER BY takenAt ASC")
    suspend fun findPhotosByShipment(shipmentId: String): List<RemoteShipmentPhotoEntity>
}
