package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.MutationEntity

@Dao
interface MutationDao {

    @Upsert
    suspend fun upsert(entity: MutationEntity)

    @Query("SELECT * FROM mutations WHERE conflictPending = 0 ORDER BY createdAt ASC")
    suspend fun listPending(): List<MutationEntity>

    @Query("SELECT * FROM mutations WHERE conflictPending = 1 ORDER BY createdAt ASC")
    suspend fun listConflicts(): List<MutationEntity>

    @Query("SELECT * FROM mutations WHERE entityType = :type AND entityId = :entityId")
    suspend fun findFor(type: String, entityId: String): List<MutationEntity>

    @Query("DELETE FROM mutations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM mutations WHERE entityType = :type AND entityId = :entityId")
    suspend fun deleteFor(type: String, entityId: String)

    @Query("SELECT COUNT(*) FROM mutations WHERE conflictPending = 0")
    suspend fun countPending(): Int
}
