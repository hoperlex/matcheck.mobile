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

    @androidx.room.Upsert
    suspend fun upsertPhoto(photo: RemoteDeliveryPhotoEntity)

    @Query("SELECT * FROM remote_delivery_photos WHERE uploadStatus IN (:statuses)")
    suspend fun findPhotosByStatus(statuses: List<String>): List<RemoteDeliveryPhotoEntity>

    @Query("SELECT * FROM remote_delivery_photos WHERE id = :id")
    suspend fun findPhotoById(id: String): RemoteDeliveryPhotoEntity?

    @Transaction
    suspend fun saveAggregate(
        delivery: RemoteDeliveryEntity,
        items: List<RemoteDeliveryItemEntity>,
        photos: List<RemoteDeliveryPhotoEntity>,
    ) {
        upsert(delivery)
        deleteItemsByDelivery(delivery.id)
        if (items.isNotEmpty()) replaceItems(items)
        deletePhotosByDelivery(delivery.id)
        if (photos.isNotEmpty()) replacePhotos(photos)
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

    @Query("SELECT * FROM remote_delivery_items WHERE deliveryId = :deliveryId ORDER BY lineNo ASC")
    suspend fun findItemsByDelivery(deliveryId: String): List<RemoteDeliveryItemEntity>
}
