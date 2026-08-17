package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.DeliveryLocalMetaDao
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.entity.DeliveryLocalMetaEntity
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertItem
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import com.example.matcheckmobile.domain.model.PhotoIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Local-first API над приёмками. UI всегда работает с этим репозиторием,
 * push на сервер происходит асинхронно через [MutationProcessor].
 *
 * id сущности и items — UUID v4, генерируемые на клиенте. Сервер принимает
 * клиентский id в upsert как есть, что даёт стабильную идентичность между
 * Room и БД на сервере.
 */
class DeliveryRepository(
    private val deliveryDao: RemoteDeliveryDao,
    private val mutationDao: MutationDao,
    private val localMetaDao: DeliveryLocalMetaDao,
    private val tx: TransactionRunner,
) {

    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun observeActive(): Flow<List<RemoteDeliveryEntity>> = deliveryDao.observeActive()
    fun observeTrash(): Flow<List<RemoteDeliveryEntity>> = deliveryDao.observeTrash()
    fun observeByStatus(status: String): Flow<List<RemoteDeliveryEntity>> =
        deliveryDao.observeByStatus(status)
    fun observeByStatuses(statuses: List<String>): Flow<List<RemoteDeliveryEntity>> =
        deliveryDao.observeByStatuses(statuses)

    suspend fun findById(id: String): RemoteDeliveryEntity? = deliveryDao.findById(id)

    suspend fun findItemsByDelivery(deliveryId: String): List<RemoteDeliveryItemEntity> =
        deliveryDao.findItemsByDelivery(deliveryId)

    /**
     * Локальный тип транспорта (Ларгус/Газель/Грузовик/Фура). Хранится только
     * на устройстве — на сервере поля нет, поэтому /sync его не затирает.
     * Поток с актуальным значением для конкретной приёмки — для подписки в UI.
     */
    fun observeVehicleType(deliveryId: String): Flow<String?> =
        localMetaDao.observeVehicleTypeCode(deliveryId)

    suspend fun getVehicleType(deliveryId: String): String? =
        localMetaDao.getByDeliveryId(deliveryId)?.vehicleTypeCode

    suspend fun setVehicleType(deliveryId: String, code: String?) {
        localMetaDao.upsert(DeliveryLocalMetaEntity(deliveryId = deliveryId, vehicleTypeCode = code))
    }

    /**
     * Канонизация natural key: sorted() перед encode гарантирует
     * стабильный ключ независимо от порядка sourceDocumentIds. Для
     * одноэлементного случая (Stage1FormViewModel.finalizeStage1)
     * no-op; защищает future multi-УПД, где [A,B] и [B,A]
     * семантически идентичны (это множество, не sequence).
     *
     * Используется и при lookup (findByNaturalKey), и при построении
     * entity — иначе после первого upsert БД будет хранить
     * неканонизированный ключ, и retry-lookup его не найдёт.
     */
    private fun canonicalSourceDocumentIdsJson(ids: List<String>): String =
        RemoteMappers.encodeIdList(ids.sorted())

    /**
     * Создаёт черновик / обновляет существующую приёмку и ставит upsert-мутацию.
     * id — клиентский UUID (можно null — сгенерим).
     *
     * Natural-key dedup: если id не передан явно и есть sourceDocumentIds,
     * ищем существующую активную приёмку по (siteId + statusCode +
     * canonicalSourceDocumentIdsJson) и переиспользуем её id. Это защита
     * от retry после ошибки фото, process-kill и double-tap — повторный
     * вызов finalizeStage1 не создаёт новую запись, а обновляет уже
     * созданную. Empty-draft'ы (пустой sourceDocumentIds) не дедупим —
     * у них нет уникального natural key, создание двух подряд легитимно.
     * Ручные внос/вынос попадают именно в эту ветку, поэтому они обязаны
     * передавать стабильный id (localDraftId) — иначе повтор после ошибки
     * фото заводит вторую запись.
     *
     * **Всё тело — одна транзакция.** Сущность, позиции и мутация должны
     * появляться и исчезать вместе: раньше агрегат сохранялся отдельно от
     * мутации, и смерть процесса между ними оставляла завершённую приёмку,
     * которой нет в очереди отправки.
     */
    suspend fun upsert(input: UpsertInput): String = tx.run {
        val sourceDocIdsJson = canonicalSourceDocumentIdsJson(input.sourceDocumentIds)
        val existingId: String? = if (input.id == null && input.sourceDocumentIds.isNotEmpty()) {
            deliveryDao.findByNaturalKey(
                siteId = input.siteId,
                statusCode = input.statusCode,
                sourceDocumentIdsJson = sourceDocIdsJson,
            )?.id
        } else null
        val id = input.id ?: existingId ?: UUID.randomUUID().toString()
        val now = currentIsoTimestamp()
        val existing = deliveryDao.findById(id)
        val baseVersion = existing?.version ?: 0

        // Времена операции — липкие: однажды записанные, они больше не
        // сдвигаются. Иначе повтор после ошибки фото, double-tap или
        // перезапуск процесса переставили бы «заезд» и «выезд» на момент
        // повтора. Решение принимается здесь, а не в UI: репозиторий —
        // единственное место, через которое проходят все вызовы.
        val effectiveArrivedAt = existing?.arrivedAt ?: input.arrivedAt
        val effectiveConfirmedAt = existing?.confirmedByMolAt
            ?: if (input.statusCode == CONFIRMED_MOL) {
                // Ручной внос шлёт одно и то же время в оба поля; для 2 Этапа
                // приходит момент нажатия «Завершить». Откат на время операции
                // страхует от вызывающего кода, который поле не передал.
                input.confirmedByMolAt ?: effectiveArrivedAt
            } else {
                null
            }

        val items = input.items.mapIndexed { idx, it ->
            RemoteDeliveryItemEntity(
                id = it.id ?: UUID.randomUUID().toString(),
                deliveryId = id,
                materialId = it.materialId,
                sourceDocumentId = it.sourceDocumentId,
                sourceDocumentItemId = it.sourceDocumentItemId,
                nameRaw = it.nameRaw,
                qtyPlanned = it.qtyPlanned,
                qtyActual = it.qtyActual,
                unit = it.unit,
                comment = it.comment,
                lineNo = it.lineNo ?: (idx + 1),
                volumeM3 = it.volumeM3,
                massKg = it.massKg,
                price = it.price,
                vatRate = it.vatRate,
                vatSum = it.vatSum,
                volumeConfidence = it.volumeConfidence,
                groupName = it.groupName,
            )
        }

        val entity = RemoteDeliveryEntity(
            id = id,
            statusCode = input.statusCode,
            statusLabel = input.statusLabel ?: input.statusCode,
            statusColor = input.statusColor,
            siteId = input.siteId,
            supplierId = input.supplierId,
            contractorId = input.contractorId,
            recipientMolId = input.recipientMolId,
            vehiclePlate = input.vehiclePlate,
            driverName = input.driverName,
            arrivedAt = effectiveArrivedAt,
            inspectorId = input.inspectorId,
            comment = input.comment,
            inTransit = input.inTransit,
            isAssets = input.isAssets,
            // Автора подтверждения знает только сервер — сохраняем то, что уже
            // пришло с него, вместо жёсткого null: иначе локальный upsert
            // затирал бы «Подтверждено МОЛ (кто)» до следующего pull.
            confirmedByMolUserId = existing?.confirmedByMolUserId,
            confirmedByMolUserEmail = existing?.confirmedByMolUserEmail,
            confirmedByMolAt = effectiveConfirmedAt,
            pendingDeletionAt = null,
            pendingDeletionByUserId = null,
            pendingDeletionByUserEmail = null,
            pendingDeletionReason = null,
            version = baseVersion,
            // Канонизированный JSON — тот же формат, что использует natural-key
            // lookup. Иначе первый upsert положит неканонизированный ключ и
            // retry-lookup его не найдёт.
            sourceDocumentIdsJson = sourceDocIdsJson,
            createdAt = now,
            updatedAt = now,
            conflictPending = false,
            serverSnapshotJson = null,
            lastSyncError = null,
        )
        // Фото кладём ТОЙ ЖЕ транзакцией, что сущность и мутацию ниже: раньше
        // подготовка кадров шла после upsert отдельным циклом, и её падение
        // (нет места, OOM) оставляло приёмку в очереди совсем без фото.
        deliveryDao.saveAggregate(
            delivery = entity,
            items = items,
            photos = emptyList(),
            photoIntents = input.photos.map { it.toDeliveryPhotoEntity(id) },
        )

        val request = DeliveryUpsertRequest(
            id = id,
            statusCode = input.statusCode,
            siteId = input.siteId,
            supplierId = input.supplierId,
            contractorId = input.contractorId,
            recipientMolId = input.recipientMolId,
            vehiclePlate = input.vehiclePlate,
            driverName = input.driverName,
            arrivedAt = effectiveArrivedAt,
            // Сервер примет это время как момент подтверждения (с проверкой по
            // своим часам и по arrivedAt). Сборки сервера без поля его
            // проигнорируют и поставят своё — контракт совместим.
            confirmedByMolAt = effectiveConfirmedAt,
            comment = input.comment,
            inTransit = input.inTransit,
            isAssets = input.isAssets,
            sourceDocumentIds = input.sourceDocumentIds,
            items = items.map {
                DeliveryUpsertItem(
                    id = it.id,
                    materialId = it.materialId,
                    sourceDocumentId = it.sourceDocumentId,
                    sourceDocumentItemId = it.sourceDocumentItemId,
                    nameRaw = it.nameRaw,
                    qtyPlanned = it.qtyPlanned,
                    qtyActual = it.qtyActual,
                    unit = it.unit,
                    comment = it.comment,
                    lineNo = it.lineNo,
                    volumeM3 = it.volumeM3,
                    massKg = it.massKg,
                    price = it.price,
                    vatRate = it.vatRate,
                    vatSum = it.vatSum,
                    volumeConfidence = it.volumeConfidence,
                    groupName = it.groupName,
                )
            },
            baseVersion = baseVersion,
        )

        // Если на этой сущности уже был upsert в очереди — заменяем его свежим.
        mutationDao.deleteFor("delivery", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "upsert",
                entityId = id,
                baseVersion = baseVersion,
                payloadJson = json.encodeToString(DeliveryUpsertRequest.serializer(), request),
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
        id
    }

    /**
     * Помечает приёмку на удаление (для filled / confirmed_mol). Локально
     * ставим pendingDeletionAt оптимистично — UI сразу переходит в read-only.
     */
    suspend fun markForDeletion(id: String, reason: String?): Result<Unit> = runCatching {
        val current = deliveryDao.findById(id) ?: return@runCatching
        val now = currentIsoTimestamp()
        deliveryDao.upsert(
            current.copy(
                pendingDeletionAt = now,
                pendingDeletionReason = reason,
            ),
        )
        mutationDao.deleteFor("delivery", id) // снимаем конкурирующие upsert/unmark
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "mark_deletion",
                entityId = id,
                baseVersion = current.version,
                payloadJson = json.encodeToString(MarkDeletionRequest.serializer(), MarkDeletionRequest(reason)),
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unmarkDeletion(id: String): Result<Unit> = runCatching {
        val current = deliveryDao.findById(id) ?: return@runCatching
        deliveryDao.upsert(
            current.copy(
                pendingDeletionAt = null,
                pendingDeletionByUserId = null,
                pendingDeletionByUserEmail = null,
                pendingDeletionReason = null,
            ),
        )
        mutationDao.deleteFor("delivery", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "unmark_deletion",
                entityId = id,
                baseVersion = current.version,
                payloadJson = null,
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Удалить приёмку. Прямой DELETE разрешён только для draft / not_filled
     * (см. soft-delete семантику в MOBILE_API.md). Для остальных — нужен
     * предварительный mark-deletion и роль admin.
     */
    suspend fun delete(id: String): Result<Unit> = runCatching {
        val current = deliveryDao.findById(id) ?: return@runCatching
        if (current.statusCode !in DELETABLE_STATUSES) {
            error("delete blocked for status=${current.statusCode}, нужно mark-deletion")
        }
        deliveryDao.deleteByIds(listOf(id))
        mutationDao.deleteFor("delivery", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "delete",
                entityId = id,
                baseVersion = current.version,
                payloadJson = null,
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun currentIsoTimestamp(): String {
        // ISO-8601 для совместимости с сервером — серверный updatedAt всё равно перепишет.
        return java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString()
    }

    /** Минимальный набор полей для upsert. UI собирает из своих view-models. */
    data class UpsertInput(
        val id: String? = null,
        val statusCode: String,
        val statusLabel: String? = null,
        val statusColor: String? = null,
        val siteId: String,
        val supplierId: String? = null,
        val contractorId: String? = null,
        val recipientMolId: String? = null,
        val vehiclePlate: String? = null,
        val driverName: String? = null,
        val arrivedAt: String? = null,
        /**
         * Момент нажатия «Завершить» на планшете. Для ручного вноса — то же
         * значение, что и [arrivedAt]. Репозиторий использует его только при
         * ПЕРВОМ подтверждении: дальше время липкое (см. upsert).
         */
        val confirmedByMolAt: String? = null,
        val inspectorId: String? = null,
        val comment: String? = null,
        /** Транзит — см. DeliveryDto.inTransit. */
        val inTransit: Boolean = false,
        /** ОС — см. DeliveryDto.isAssets. */
        val isAssets: Boolean = false,
        val sourceDocumentIds: List<String> = emptyList(),
        val items: List<ItemInput> = emptyList(),
        /**
         * Кадры, снятые в форме. Ложатся в Room ТОЙ ЖЕ транзакцией, что и
         * мутация: приёмка не может уехать на сервер без своих фото.
         * Подготовку (декод + два JPEG) делает потом PhotoPrepareWorker.
         */
        val photos: List<PhotoIntent> = emptyList(),
    )

    data class ItemInput(
        val id: String? = null,
        val materialId: String? = null,
        /**
         * Происхождение позиции: документ и его строка. null у строк, которые
         * инспектор добавил руками. Обязательно для приёмки по нескольким УПД
         * одной машины — иначе на веб-портале позиции не раскладываются по
         * документам. Сервер отбрасывает документ, не привязанный к приёмке.
         */
        val sourceDocumentId: String? = null,
        val sourceDocumentItemId: String? = null,
        val nameRaw: String,
        val qtyPlanned: String? = null,
        val qtyActual: String? = null,
        val unit: String = "шт",
        val comment: String? = null,
        val lineNo: Int? = null,
        val volumeM3: String? = null,
        val massKg: String? = null,
        val price: String? = null,
        val vatRate: String? = null,
        val vatSum: String? = null,
        val volumeConfidence: String? = null,
        val groupName: String? = null,
    )

    private companion object {
        val DELETABLE_STATUSES = setOf("draft", "not_filled")
        const val CONFIRMED_MOL = "confirmed_mol"
    }
}
