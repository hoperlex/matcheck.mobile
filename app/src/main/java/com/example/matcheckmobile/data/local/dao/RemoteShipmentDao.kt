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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePhotos(photos: List<RemoteShipmentPhotoEntity>)

    @Query("DELETE FROM remote_shipment_photos WHERE shipmentId = :shipmentId")
    suspend fun deletePhotosByShipment(shipmentId: String)

    @Query("DELETE FROM remote_shipment_photos WHERE uploadStatus = :status AND lastUploadError = :error")
    suspend fun deletePhotosByStatusAndError(status: String, error: String): Int

    @androidx.room.Upsert
    suspend fun upsertPhoto(photo: RemoteShipmentPhotoEntity)

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

    @Transaction
    suspend fun saveAggregate(
        shipment: RemoteShipmentEntity,
        items: List<RemoteShipmentItemEntity>,
        photos: List<RemoteShipmentPhotoEntity>,
    ) {
        upsert(shipment)
        deleteItemsByShipment(shipment.id)
        if (items.isNotEmpty()) replaceItems(items)
        // Photo живут своим pipeline и сервер на upsert их не возвращает —
        // если делать deletePhotos+replace, локальные PENDING_UPLOAD стираются
        // сразу после push мутации (см. комментарий в RemoteDeliveryDao).
        for (p in photos) upsertPhoto(p)
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
     * onDelete=CASCADE. См. SyncRepository.resetServerSnapshotOnSiteChange.
     */
    @Query("DELETE FROM remote_shipments")
    suspend fun deleteAll()

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
