package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.matcheckmobile.domain.model.SourceKind
import com.example.matcheckmobile.domain.model.SourceOrigin
import com.example.matcheckmobile.domain.model.SourceStatus

@Entity(
    tableName = "source_documents",
    indices = [
        Index(value = ["supplierId"]),
        Index(value = ["docDate"]),
    ],
)
data class SourceDocumentEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val kind: SourceKind,
    val status: SourceStatus,
    val supplierId: String?,
    val recipientId: String?,
    val docNumber: String?,
    val docDate: Long?,
    val totalSum: Double?,
    val vatSum: Double?,
    val expectedDate: Long?,
    val origin: SourceOrigin,
    val updatedAt: Long,
)
