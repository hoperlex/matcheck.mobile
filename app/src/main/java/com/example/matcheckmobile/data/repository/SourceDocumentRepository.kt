package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.SourceDocumentDao
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
import kotlinx.coroutines.flow.Flow

class SourceDocumentRepository(
    private val dao: SourceDocumentDao,
) {
    fun observeAll(): Flow<List<SourceDocumentEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<SourceDocumentEntity?> = dao.observeById(id)

    fun observeItems(documentId: String): Flow<List<SourceDocumentItemEntity>> =
        dao.observeItems(documentId)

    suspend fun findById(id: String): SourceDocumentEntity? = dao.findById(id)

    suspend fun itemsCount(id: String): Int = dao.itemsCount(id)
}
