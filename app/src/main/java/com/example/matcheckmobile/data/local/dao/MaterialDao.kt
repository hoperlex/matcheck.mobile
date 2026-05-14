package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.MaterialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MaterialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(material: MaterialEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(materials: List<MaterialEntity>)

    @Query("SELECT * FROM materials WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): MaterialEntity?

    @Query("SELECT * FROM materials ORDER BY name")
    fun observeAll(): Flow<List<MaterialEntity>>
}
