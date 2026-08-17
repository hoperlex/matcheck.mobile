package com.example.matcheckmobile.domain.model

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity

/**
 * Фикстура документа для тестов. Обязательных полей у сущности два десятка, а
 * тестам обычно важны два-три — держим один конструктор на всех, чтобы
 * добавление колонки правилось в одном месте, а не в каждом наборе.
 */
fun testDoc(
    id: String,
    groupId: String? = null,
    groupRevision: Int? = null,
    kind: String = "upd",
    docNumber: String? = null,
    direction: String = "inbound",
    siteId: String? = "site-1",
    expectedDate: String? = null,
    supplierId: String? = null,
    supplierName: String? = null,
    contractorId: String? = null,
    recipientMolId: String? = null,
    consigneeName: String? = null,
    buyerName: String? = null,
    createdByUserPhone: String? = null,
    siteName: String? = null,
): RemoteSourceDocumentEntity = RemoteSourceDocumentEntity(
    id = id,
    kind = kind,
    direction = direction,
    status = "unaccepted",
    supplierId = supplierId,
    recipientId = null,
    contractorId = contractorId,
    recipientMolId = recipientMolId,
    siteId = siteId,
    supplierName = supplierName,
    contractorName = null,
    consigneeName = consigneeName,
    buyerName = buyerName,
    siteName = siteName,
    createdByUserPhone = createdByUserPhone,
    docNumber = docNumber,
    docDate = null,
    totalSum = null,
    vatSum = null,
    expectedDate = expectedDate,
    origin = "manual_pdf",
    parsedAt = "2026-07-02T00:00:00Z",
    parseErrorCode = null,
    originalFilename = null,
    version = 1,
    createdAt = "2026-07-02T00:00:00Z",
    updatedAt = "2026-07-02T00:00:00Z",
    groupId = groupId,
    groupRevision = groupRevision,
)
