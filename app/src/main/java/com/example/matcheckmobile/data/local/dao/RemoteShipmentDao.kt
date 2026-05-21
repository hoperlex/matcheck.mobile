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

    @androidx.room.Upsert
    suspend fun upsertPhoto(photo: RemoteShipmentPhotoEntity)

    @Query("SELECT * FROM remote_shipment_photos WHERE uploadStatus IN (:statuses)")
    suspend fun findPhotosByStatus(statuses: List<String>): List<RemoteShipmentPhotoEntity>

    @Query("SELECT * FROM remote_shipment_photos WHERE uploadStatus IN (:statuses) ORDER BY takenAt DESC")
    fun observePhotosByStatus(statuses: List<String>): Flow<List<RemoteShipmentPhotoEntity>>

    @Query("SELECT * FROM remote_shipment_photos WHERE id = :id")
    suspend fun findPhotoById(id: String): RemoteShipmentPhotoEntity?

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

    @Query("SELECT id FROM remote_shipments WHERE conflictPending = 1")
    suspend fun listConflictPendingIds(): List<String>

    @Query("SELECT * FROM remote_shipments WHERE conflictPending = 1 ORDER BY updatedAt DESC")
    fun observeConflicts(): kotlinx.coroutines.flow.Flow<List<RemoteShipmentEntity>>

    @Query(
        """
        SELECT sourceDocumentIdsJson FROM remote_shipments
        WHERE pendingDeletionAt IS NULL AND sourceDocumentIdsJson != '[]'
        """
    )
    fun observeAttachedSourceDocumentIdsJson(): kotlinx.coroutines.flow.Flow<List<String>>
}
