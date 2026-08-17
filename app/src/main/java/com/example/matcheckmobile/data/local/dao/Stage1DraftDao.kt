package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface Stage1DraftDao {

    @Query("SELECT * FROM stage1_drafts WHERE localDraftId = :id")
    suspend fun findById(id: String): Stage1DraftEntity?

    @Query("SELECT * FROM stage1_drafts WHERE updId = :updId LIMIT 1")
    suspend fun findByUpdId(updId: String): Stage1DraftEntity?

    /** Черновик машины. UNIQUE по groupId гарантирует единственность. */
    @Query("SELECT * FROM stage1_drafts WHERE groupId = :groupId LIMIT 1")
    suspend fun findByGroupId(groupId: String): Stage1DraftEntity?

    @Query("SELECT * FROM stage1_drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Stage1DraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: Stage1DraftEntity)

    @Query("DELETE FROM stage1_drafts WHERE localDraftId = :id")
    suspend fun deleteById(id: String)

    /** Сколько черновиков ждёт на планшете — для предупреждения при смене аккаунта. */
    @Query("SELECT COUNT(*) FROM stage1_drafts")
    suspend fun count(): Int
}
