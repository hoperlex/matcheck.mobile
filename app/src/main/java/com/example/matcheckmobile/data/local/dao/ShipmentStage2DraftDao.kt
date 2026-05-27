package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.ShipmentStage2DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipmentStage2DraftDao {

    @Query("SELECT * FROM shipment_stage2_drafts WHERE shipmentId = :id")
    suspend fun findById(id: String): ShipmentStage2DraftEntity?

    @Query("SELECT * FROM shipment_stage2_drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ShipmentStage2DraftEntity>>

    @Query("SELECT shipmentId FROM shipment_stage2_drafts")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ShipmentStage2DraftEntity)

    @Query("DELETE FROM shipment_stage2_drafts WHERE shipmentId = :id")
    suspend fun deleteById(id: String)
}
