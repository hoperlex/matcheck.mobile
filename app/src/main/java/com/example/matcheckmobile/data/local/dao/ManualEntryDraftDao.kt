package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.ManualEntryDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualEntryDraftDao {

    @Query("SELECT * FROM manual_entry_drafts WHERE localDraftId = :id")
    suspend fun findById(id: String): ManualEntryDraftEntity?

    // siteId-фильтрацию делаем в VM (siteId приходит из tokenStorage.state Flow),
    // как в Stage2ListViewModel — так список fail-closed переигрывается при смене
    // объекта без отдельного запроса.
    @Query("SELECT * FROM manual_entry_drafts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ManualEntryDraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ManualEntryDraftEntity)

    @Query("DELETE FROM manual_entry_drafts WHERE localDraftId = :id")
    suspend fun deleteById(id: String)

    /** Для барьера «не потерять неотправленное» при выходе/смене аккаунта. */
    @Query("SELECT COUNT(*) FROM manual_entry_drafts")
    suspend fun count(): Int
}
