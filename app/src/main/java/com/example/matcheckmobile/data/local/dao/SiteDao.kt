package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.SiteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: SiteEntity)

    @Query("SELECT * FROM sites WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): SiteEntity?

    @Query("SELECT * FROM sites ORDER BY name")
    fun observeAll(): Flow<List<SiteEntity>>
}
