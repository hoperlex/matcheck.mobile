package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDocumentDao {
    /**
     * INSERT-or-UPDATE (без DELETE) — REPLACE снёс бы дочерние source_document_items
     * через FK CASCADE.
     */
    @Upsert
    suspend fun upsertDocument(doc: SourceDocumentEntity)

    @Upsert
    suspend fun upsertItems(items: List<SourceDocumentItemEntity>)

    @Query("SELECT * FROM source_documents WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): SourceDocumentEntity?

    @Query("SELECT * FROM source_documents WHERE localId = :id LIMIT 1")
    fun observeById(id: String): Flow<SourceDocumentEntity?>

    @Query("SELECT * FROM source_documents ORDER BY COALESCE(docDate, updatedAt) DESC")
    fun observeAll(): Flow<List<SourceDocumentEntity>>

    @Query(
        "SELECT * FROM source_documents " +
            "WHERE kind = 'UPD' AND localId NOT IN (" +
            "  SELECT sourceDocumentLocalId FROM receipt_sessions " +
            "  WHERE kind = 'RECEIPT' AND syncStatus = 'COMPLETED' " +
            "    AND sourceDocumentLocalId IS NOT NULL" +
            ") " +
            "ORDER BY COALESCE(docDate, updatedAt) DESC"
    )
    fun observeUpdWithoutCompletedReceipt(): Flow<List<SourceDocumentEntity>>

    @Query("SELECT * FROM source_document_items WHERE sourceDocumentLocalId = :docId ORDER BY lineNo")
    fun observeItems(docId: String): Flow<List<SourceDocumentItemEntity>>

    @Query("SELECT COUNT(*) FROM source_document_items WHERE sourceDocumentLocalId = :docId")
    suspend fun itemsCount(docId: String): Int

    @Query("SELECT COUNT(*) FROM source_documents")
    suspend fun count(): Int
}
