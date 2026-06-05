package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.ShipmentLocalMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipmentLocalMetaDao {

    @Upsert
    suspend fun upsert(meta: ShipmentLocalMetaEntity)

    @Query("SELECT * FROM shipment_local_meta WHERE shipmentId = :shipmentId")
    suspend fun getByShipmentId(shipmentId: String): ShipmentLocalMetaEntity?

    @Query("SELECT vehicleTypeCode FROM shipment_local_meta WHERE shipmentId = :shipmentId")
    fun observeVehicleTypeCode(shipmentId: String): Flow<String?>

    @Query("DELETE FROM shipment_local_meta WHERE shipmentId = :shipmentId")
    suspend fun deleteByShipmentId(shipmentId: String)
}
