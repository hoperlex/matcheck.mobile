package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteDeliveryDao {

    @Upsert
    suspend fun upsert(entity: RemoteDeliveryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RemoteDeliveryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceItems(items: List<RemoteDeliveryItemEntity>)

    @Query("DELETE FROM remote_delivery_items WHERE deliveryId = :deliveryId")
    suspend fun deleteItemsByDelivery(deliveryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePhotos(photos: List<RemoteDeliveryPhotoEntity>)

    @Query("DELETE FROM remote_delivery_photos WHERE deliveryId = :deliveryId")
    suspend fun deletePhotosByDelivery(deliveryId: String)

    @Query("DELETE FROM remote_delivery_photos WHERE uploadStatus = :status AND lastUploadError = :error")
    suspend fun deletePhotosByStatusAndError(status: String, error: String): Int

    @androidx.room.Upsert
    suspend fun upsertPhoto(photo: RemoteDeliveryPhotoEntity)

    @Query("SELECT * FROM remote_delivery_photos WHERE uploadStatus IN (:statuses)")
    suspend fun findPhotosByStatus(statuses: List<String>): List<RemoteDeliveryPhotoEntity>

    @Query("SELECT * FROM remote_delivery_photos WHERE uploadStatus IN (:statuses) ORDER BY takenAt DESC")
    fun observePhotosByStatus(statuses: List<String>): Flow<List<RemoteDeliveryPhotoEntity>>

    @Query("SELECT * FROM remote_delivery_photos WHERE id = :id")
    suspend fun findPhotoById(id: String): RemoteDeliveryPhotoEntity?

    @Query("DELETE FROM remote_delivery_photos WHERE id = :id")
    suspend fun deletePhotoById(id: String)

    @Transaction
    suspend fun saveAggregate(
        delivery: RemoteDeliveryEntity,
        items: List<RemoteDeliveryItemEntity>,
        photos: List<RemoteDeliveryPhotoEntity>,
    ) {
        upsert(delivery)
        deleteItemsByDelivery(delivery.id)
        if (items.isNotEmpty()) replaceItems(items)
        // Photo живут своим pipeline (PhotoUploadProcessor: presign → S3 → confirm),
        // и сервер на /deliveries upsert не возвращает их состояние (photos=[]).
        // Поэтому ВНУТРИ saveAggregate НЕ удаляем локальные фото — иначе
        // PENDING_UPLOAD-записи, только что созданные через captureForDelivery,
        // потеряются сразу после push мутации. Серверные фото мерджим upsert'ом
        // по id (merge без drop неприсланных).
        for (p in photos) upsertPhoto(p)
    }

    @Query("SELECT * FROM remote_deliveries WHERE id = :id")
    suspend fun findById(id: String): RemoteDeliveryEntity?

    @Query("SELECT * FROM remote_deliveries WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<RemoteDeliveryEntity>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE pendingDeletionAt IS NULL
        ORDER BY arrivedAt DESC, createdAt DESC
        """
    )
    fun observeActive(): Flow<List<RemoteDeliveryEntity>>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE pendingDeletionAt IS NOT NULL
        ORDER BY pendingDeletionAt DESC
        """
    )
    fun observeTrash(): Flow<List<RemoteDeliveryEntity>>

    @Query("DELETE FROM remote_deliveries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT id FROM remote_deliveries WHERE conflictPending = 1")
    suspend fun listConflictPendingIds(): List<String>

    @Query("SELECT * FROM remote_deliveries WHERE conflictPending = 1 ORDER BY updatedAt DESC")
    fun observeConflicts(): kotlinx.coroutines.flow.Flow<List<RemoteDeliveryEntity>>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE statusCode = :status AND pendingDeletionAt IS NULL
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeByStatus(status: String): Flow<List<RemoteDeliveryEntity>>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE statusCode IN (:statuses) AND pendingDeletionAt IS NULL
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeByStatuses(statuses: List<String>): Flow<List<RemoteDeliveryEntity>>

    @Query("SELECT * FROM remote_delivery_items WHERE deliveryId = :deliveryId ORDER BY lineNo ASC")
    suspend fun findItemsByDelivery(deliveryId: String): List<RemoteDeliveryItemEntity>

    @Query(
        """
        SELECT sourceDocumentIdsJson FROM remote_deliveries
        WHERE pendingDeletionAt IS NULL AND sourceDocumentIdsJson != '[]'
        """
    )
    fun observeAttachedSourceDocumentIdsJson(): Flow<List<String>>
}
