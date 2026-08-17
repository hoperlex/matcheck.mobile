package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentAttachmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteSourceDocumentDao {

    @Upsert
    suspend fun upsert(entity: RemoteSourceDocumentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RemoteSourceDocumentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceItems(items: List<RemoteSourceDocumentItemEntity>)

    @Query("DELETE FROM remote_source_document_items WHERE sourceDocumentId = :id")
    suspend fun deleteItemsBySource(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAttachments(attachments: List<RemoteSourceDocumentAttachmentEntity>)

    @Query("DELETE FROM remote_source_document_attachments WHERE sourceDocumentId = :id")
    suspend fun deleteAttachmentsBySource(id: String)

    @Transaction
    suspend fun saveAggregate(
        doc: RemoteSourceDocumentEntity,
        items: List<RemoteSourceDocumentItemEntity>,
        attachments: List<RemoteSourceDocumentAttachmentEntity>,
    ) {
        upsert(doc)
        deleteItemsBySource(doc.id)
        if (items.isNotEmpty()) replaceItems(items)
        deleteAttachmentsBySource(doc.id)
        if (attachments.isNotEmpty()) replaceAttachments(attachments)
    }

    @Query("SELECT * FROM remote_source_documents WHERE id = :id")
    suspend fun findById(id: String): RemoteSourceDocumentEntity?

    @Query(
        """
        SELECT * FROM remote_source_document_items
        WHERE sourceDocumentId = :sourceDocumentId
        ORDER BY lineNo ASC
        """
    )
    suspend fun findItemsBySource(sourceDocumentId: String): List<RemoteSourceDocumentItemEntity>

    /**
     * Все документы одной «машины». Порядок задаёт не SQL, а
     * [com.example.matcheckmobile.domain.model.sortGroupDocs]: от него зависят
     * и заголовок карточки, и сквозная нумерация позиций, и приоритет
     * реквизитов — держать это правило в двух местах нельзя.
     */
    @Query("SELECT * FROM remote_source_documents WHERE groupId = :groupId")
    suspend fun findByGroupId(groupId: String): List<RemoteSourceDocumentEntity>

    @Query("SELECT * FROM remote_source_documents ORDER BY docDate DESC")
    fun observeAll(): Flow<List<RemoteSourceDocumentEntity>>

    @Query("DELETE FROM remote_source_documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Полная очистка серверного snapshot — для smart-reset при смене
     * user.siteId в админке (см. SiteChangeReset.resumeIfNeeded).
     * items/attachments удалятся каскадом по FK с onDelete=CASCADE.
     */
    @Query("DELETE FROM remote_source_documents")
    suspend fun deleteAll()

    // Лёгкая проекция (id, version) всех локальных УПД — для reconcile-сверки
    // (read-only). См. SyncRepository.reconcileOnce.
    @Query("SELECT id, version FROM remote_source_documents")
    suspend fun listReconcileVersions(): List<ReconcileVersionRow>
}
