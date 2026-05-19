package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.DeliveryLocalMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryLocalMetaDao {

    @Upsert
    suspend fun upsert(meta: DeliveryLocalMetaEntity)

    @Query("SELECT * FROM delivery_local_meta WHERE deliveryId = :deliveryId")
    suspend fun getByDeliveryId(deliveryId: String): DeliveryLocalMetaEntity?

    @Query("SELECT vehicleTypeCode FROM delivery_local_meta WHERE deliveryId = :deliveryId")
    fun observeVehicleTypeCode(deliveryId: String): Flow<String?>

    @Query("DELETE FROM delivery_local_meta WHERE deliveryId = :deliveryId")
    suspend fun deleteByDeliveryId(deliveryId: String)
}
