package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import kotlinx.coroutines.flow.Flow

/** Карантин операций группового протокола. См. [QuarantinedOperationEntity]. */
@Dao
interface QuarantinedOperationDao {

    /**
     * ABORT, а не REPLACE: id — свежий UUID, и коллизия означала бы ошибку
     * программы. Молча заменить снимок чужой операции нельзя.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: QuarantinedOperationEntity)

    @Query("SELECT * FROM quarantined_operations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QuarantinedOperationEntity>>

    @Query("SELECT * FROM quarantined_operations WHERE id = :id")
    suspend fun findById(id: String): QuarantinedOperationEntity?

    @Query("SELECT * FROM quarantined_operations WHERE entityType = :type AND entityId = :entityId")
    suspend fun findByEntity(type: String, entityId: String): List<QuarantinedOperationEntity>

    @Query("UPDATE quarantined_operations SET reissuedAt = :at WHERE id = :id")
    suspend fun markReissued(id: String, at: Long)

    @Query("DELETE FROM quarantined_operations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM quarantined_operations")
    suspend fun count(): Int

    @Query("SELECT id FROM quarantined_operations")
    suspend fun listIds(): List<String>
}
