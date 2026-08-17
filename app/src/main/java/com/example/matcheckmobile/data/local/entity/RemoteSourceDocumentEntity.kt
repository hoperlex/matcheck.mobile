package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная копия SourceDocumentDetail из контракта. На мобиле УПД только
 * читаем + привязываем к приёмке/отгрузке через sourceDocumentIds.
 */
@Entity(
    tableName = "remote_source_documents",
    indices = [
        Index(value = ["siteId"]),
        Index(value = ["supplierId"]),
        Index(value = ["status"]),
        Index(value = ["docDate"]),
        Index(value = ["groupId"]),
    ],
)
data class RemoteSourceDocumentEntity(
    @PrimaryKey val id: String,
    val kind: String, // 'upd' | 'request'
    val direction: String, // 'inbound' | 'outbound'
    val status: String,
    val supplierId: String?,
    val recipientId: String?,
    val contractorId: String?,
    val recipientMolId: String?,
    val siteId: String?,
    val supplierName: String?,
    val contractorName: String?,
    // Грузополучатель (графа 4 УПД) — то, что показывается в строке карточки
    // на экранах выбора УПД. null для документов, у которых парсер графу 4
    // не распознал, — тогда в UI прочерк.
    val consigneeName: String?,
    // Покупатель (графа 6). Вторая ступень подписи, когда графа 4 не
    // распозналась: подрядчика в списках УПД не показывают.
    val buyerName: String?,
    val siteName: String?,
    // Телефон автора УПД (того, кто загрузил её через веб-портал). Нужен
    // на 1 Этапе приёмки для иконки звонка в шапке «Материалы». null для
    // УПД из EDO/mail или загруженных до миграции 0039 — иконка просто
    // не показывается. email/userId на мобиле пока не нужны, держим только
    // в DTO; если понадобится — добавим колонки отдельной миграцией.
    val createdByUserPhone: String?,
    val docNumber: String?,
    val docDate: String?,
    val totalSum: String?,
    val vatSum: String?,
    val expectedDate: String?,
    val origin: String,
    val parsedAt: String,
    val parseErrorCode: String?,
    val originalFilename: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    /**
     * «Машина»: id корневого пакета загрузки на сервере. Одна карточка
     * «Машина N» на веб-портале /uploads = один пакет, из которого может выйти
     * несколько документов (две УПД + транспортная накладная). Документы с
     * одним groupId склеиваются в одну карточку списка и одну приёмку — иначе
     * инспектор оформляет одну и ту же машину несколько раз.
     *
     * null — группировать нельзя: legacy-сборка на сервере («файл = документ»,
     * многостраничная УПД могла стать N документами с частичными позициями) или
     * документ пришёл не пакетом (EDO/mail). Тогда документ сам себе группа.
     */
    val groupId: String?,
    /**
     * Версия состава группы с сервера. Растёт и когда в машину добавился
     * документ, и когда поменялись реквизиты или позиции любого из её
     * документов. Форма 1 Этапа запоминает значение на момент загрузки и
     * сверяет перед финализацией.
     */
    val groupRevision: Int?,
)

@Entity(
    tableName = "remote_source_document_items",
    foreignKeys = [
        ForeignKey(
            entity = RemoteSourceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceDocumentId"])],
)
data class RemoteSourceDocumentItemEntity(
    @PrimaryKey val id: String,
    val sourceDocumentId: String,
    val materialId: String?,
    val nameRaw: String,
    val qty: String,
    val unit: String,
    val price: String?,
    val sum: String?,
    val vatRate: String?,
    val vatSum: String?,
    val expectedDate: String?,
    val lineNo: Int,
    val volumeM3: String?,
    val massKg: String?,
    val volumeConfidence: String?,
    val groupName: String?,
)

@Entity(
    tableName = "remote_source_document_attachments",
    foreignKeys = [
        ForeignKey(
            entity = RemoteSourceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceDocumentId"])],
)
data class RemoteSourceDocumentAttachmentEntity(
    @PrimaryKey val id: String,
    val sourceDocumentId: String,
    val s3Key: String,
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val role: String, // 'original' | 'extracted_text'
)
