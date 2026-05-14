package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_document_items",
    foreignKeys = [
        ForeignKey(
            entity = SourceDocumentEntity::class,
            parentColumns = ["localId"],
            childColumns = ["sourceDocumentLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceDocumentLocalId"])],
)
data class SourceDocumentItemEntity(
    @PrimaryKey val localId: String,
    val sourceDocumentLocalId: String,
    val materialId: String?,
    val nameRaw: String,
    val qty: Double,
    val unit: String,
    val price: Double?,
    val sum: Double?,
    val lineNo: Int,
)
