package com.example.matcheckmobile.data.local.mapper

import com.example.matcheckmobile.data.local.entity.RemoteCounterpartyEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteMaterialEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteSiteEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentAttachmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteStatusEntity
import com.example.matcheckmobile.data.remote.api.dto.CounterpartyDto
import com.example.matcheckmobile.data.remote.api.dto.DeliveryDto
import com.example.matcheckmobile.data.remote.api.dto.DeliveryItemDto
import com.example.matcheckmobile.data.remote.api.dto.DeliveryPhotoDto
import com.example.matcheckmobile.data.remote.api.dto.MaterialDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentItemDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentPhotoDto
import com.example.matcheckmobile.data.remote.api.dto.SiteDto
import com.example.matcheckmobile.data.remote.api.dto.SourceAttachmentDto
import com.example.matcheckmobile.data.remote.api.dto.SourceDocumentDto
import com.example.matcheckmobile.data.remote.api.dto.SourceItemDto
import com.example.matcheckmobile.data.remote.api.dto.StatusDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Чистые DTO → Entity конвертеры. Шарятся между SyncRepository (pull-сценарий)
 * и MutationProcessor (после успешного upsert/mark/unmark обновляем local).
 *
 * sourceDocumentIds сериализуется как JSON-массив в одну колонку, чтобы не
 * плодить таблицу связи под список UUID — обычно это 0..2 элемента.
 */
object RemoteMappers {

    private val json: Json = Json { encodeDefaults = true }
    private val stringListSerializer = ListSerializer(String.serializer())

    fun encodeIdList(ids: List<String>): String = json.encodeToString(stringListSerializer, ids)
    fun decodeIdList(raw: String): List<String> = runCatching {
        json.decodeFromString(stringListSerializer, raw)
    }.getOrDefault(emptyList())

    fun CounterpartyDto.toEntity(): RemoteCounterpartyEntity = RemoteCounterpartyEntity(
        id = id, inn = inn, kpp = kpp, name = name, address = address,
        isSelf = isSelf, isSupplier = isSupplier, isCustomer = isCustomer,
        isContractor = isContractor, createdAt = createdAt, updatedAt = updatedAt,
    )

    fun MaterialDto.toEntity(): RemoteMaterialEntity = RemoteMaterialEntity(
        id = id, code = code, name = name, unit = unit,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    fun SiteDto.toEntity(): RemoteSiteEntity = RemoteSiteEntity(
        id = id, code = code, name = name, fullName = fullName, address = address,
        isActive = isActive, createdAt = createdAt, updatedAt = updatedAt,
    )

    fun StatusDto.toEntity(): RemoteStatusEntity = RemoteStatusEntity(
        id = id, entityType = entityType, code = code, label = label,
        color = color, sortOrder = sortOrder,
    )

    fun DeliveryDto.toEntity(): RemoteDeliveryEntity = RemoteDeliveryEntity(
        id = id,
        statusCode = status.code,
        statusLabel = status.label,
        statusColor = status.color,
        siteId = siteId,
        supplierId = supplierId,
        contractorId = contractorId,
        recipientMolId = recipientMolId,
        vehiclePlate = vehiclePlate,
        driverName = driverName,
        arrivedAt = arrivedAt,
        inspectorId = inspectorId,
        comment = comment,
        confirmedByMolUserId = confirmedByMolUserId,
        confirmedByMolUserEmail = confirmedByMolUserEmail,
        confirmedByMolAt = confirmedByMolAt,
        pendingDeletionAt = pendingDeletionAt,
        pendingDeletionByUserId = pendingDeletionByUserId,
        pendingDeletionByUserEmail = pendingDeletionByUserEmail,
        pendingDeletionReason = pendingDeletionReason,
        version = version,
        sourceDocumentIdsJson = encodeIdList(sourceDocumentIds),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun DeliveryItemDto.toEntity(deliveryId: String): RemoteDeliveryItemEntity = RemoteDeliveryItemEntity(
        id = id, deliveryId = deliveryId, materialId = materialId, nameRaw = nameRaw,
        qtyPlanned = qtyPlanned, qtyActual = qtyActual, unit = unit, comment = comment,
        lineNo = lineNo, volumeM3 = volumeM3, massKg = massKg,
        price = price, vatRate = vatRate, vatSum = vatSum,
        volumeConfidence = volumeConfidence, groupName = groupName,
    )

    /**
     * Серверный фото-DTO (приходит уже uploaded). idempotencyKey и contentType
     * на сервере не хранятся — клиенту они нужны только для локально-инициированной
     * загрузки. Заполняем заглушками: при следующем capture эти поля не используются,
     * а на отображение влияет только s3Key.
     */
    fun DeliveryPhotoDto.toEntity(deliveryId: String): RemoteDeliveryPhotoEntity = RemoteDeliveryPhotoEntity(
        id = id, deliveryId = deliveryId, kind = kind,
        stage = stage,
        s3Key = s3Key,
        thumbS3Key = thumbS3Key, contentHash = contentHash, takenAt = takenAt,
        uploadedAt = uploadedAt,
        idempotencyKey = "",
        contentType = "image/jpeg",
        localBlobPath = null,
        localThumbPath = null,
        uploadStatus = if (uploadedAt != null) "UPLOADED" else "PENDING_UPLOAD",
        lastUploadError = null,
    )

    fun ShipmentDto.toEntity(): RemoteShipmentEntity = RemoteShipmentEntity(
        id = id,
        statusCode = status.code,
        statusLabel = status.label,
        statusColor = status.color,
        kind = kind,
        purpose = purpose,
        siteId = siteId,
        receiverCounterpartyId = receiverCounterpartyId,
        receiverMolId = receiverMolId,
        destSiteId = destSiteId,
        vehiclePlate = vehiclePlate,
        driverName = driverName,
        shippedAt = shippedAt,
        inspectorId = inspectorId,
        comment = comment,
        confirmedByMolUserId = confirmedByMolUserId,
        confirmedByMolUserEmail = confirmedByMolUserEmail,
        confirmedByMolAt = confirmedByMolAt,
        pendingDeletionAt = pendingDeletionAt,
        pendingDeletionByUserId = pendingDeletionByUserId,
        pendingDeletionByUserEmail = pendingDeletionByUserEmail,
        pendingDeletionReason = pendingDeletionReason,
        version = version,
        sourceDocumentIdsJson = encodeIdList(sourceDocumentIds),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun ShipmentItemDto.toEntity(shipmentId: String): RemoteShipmentItemEntity = RemoteShipmentItemEntity(
        id = id, shipmentId = shipmentId, materialId = materialId, nameRaw = nameRaw,
        qtyPlanned = qtyPlanned, qtyActual = qtyActual, unit = unit, comment = comment,
        lineNo = lineNo, volumeM3 = volumeM3, massKg = massKg,
        price = price, vatRate = vatRate, vatSum = vatSum,
        volumeConfidence = volumeConfidence, groupName = groupName,
    )

    fun ShipmentPhotoDto.toEntity(shipmentId: String): RemoteShipmentPhotoEntity = RemoteShipmentPhotoEntity(
        id = id, shipmentId = shipmentId, kind = kind, stage = stage, s3Key = s3Key,
        thumbS3Key = thumbS3Key, contentHash = contentHash, takenAt = takenAt,
        uploadedAt = uploadedAt,
        idempotencyKey = "",
        contentType = "image/jpeg",
        localBlobPath = null,
        localThumbPath = null,
        uploadStatus = if (uploadedAt != null) "UPLOADED" else "PENDING_UPLOAD",
        lastUploadError = null,
    )

    fun SourceDocumentDto.toEntity(): RemoteSourceDocumentEntity = RemoteSourceDocumentEntity(
        id = id, kind = kind, direction = direction, status = status,
        supplierId = supplierId, recipientId = recipientId, contractorId = contractorId,
        recipientMolId = recipientMolId,
        siteId = siteId, supplierName = supplierName, contractorName = contractorName,
        siteName = siteName, createdByUserPhone = createdByUserPhone,
        docNumber = docNumber, docDate = docDate,
        totalSum = totalSum, vatSum = vatSum, expectedDate = expectedDate, origin = origin,
        parsedAt = parsedAt, parseErrorCode = parseErrorCode, originalFilename = originalFilename,
        version = version, createdAt = createdAt, updatedAt = updatedAt,
    )

    fun SourceItemDto.toEntity(docId: String): RemoteSourceDocumentItemEntity = RemoteSourceDocumentItemEntity(
        id = id, sourceDocumentId = docId, materialId = materialId, nameRaw = nameRaw,
        qty = qty, unit = unit, price = price, sum = sum, vatRate = vatRate,
        vatSum = vatSum, expectedDate = expectedDate, lineNo = lineNo,
        volumeM3 = volumeM3, massKg = massKg, volumeConfidence = volumeConfidence,
        groupName = groupName,
    )

    fun SourceAttachmentDto.toEntity(docId: String): RemoteSourceDocumentAttachmentEntity =
        RemoteSourceDocumentAttachmentEntity(
            id = id, sourceDocumentId = docId, s3Key = s3Key, filename = filename,
            mimeType = mimeType, sizeBytes = sizeBytes, role = role,
        )
}
