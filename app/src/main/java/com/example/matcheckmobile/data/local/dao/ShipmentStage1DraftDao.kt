package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.ShipmentStage1DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipmentStage1DraftDao {

    @Query("SELECT * FROM shipment_stage1_drafts WHERE localDraftId = :id")
    suspend fun findById(id: String): ShipmentStage1DraftEntity?

    @Query("SELECT * FROM shipment_stage1_drafts WHERE updId = :updId LIMIT 1")
    suspend fun findByUpdId(updId: String): ShipmentStage1DraftEntity?

    @Query("SELECT * FROM shipment_stage1_drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ShipmentStage1DraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ShipmentStage1DraftEntity)

    @Query("DELETE FROM shipment_stage1_drafts WHERE localDraftId = :id")
    suspend fun deleteById(id: String)

    /** Сколько черновиков ждёт на планшете — для предупреждения при смене аккаунта. */
    @Query("SELECT COUNT(*) FROM shipment_stage1_drafts")
    suspend fun count(): Int
}
