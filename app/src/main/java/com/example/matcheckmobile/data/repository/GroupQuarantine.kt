package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers.toUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.media.createUniquePhotoFile
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Карантин операций группового протокола «одна машина — одна приёмка».
 *
 * Сервер отверг создание приёмки typed-конфликтом: машину уже оформил другой
 * планшет (`group_claimed`) или снимок формы устарел (`group_changed`,
 * `group_incomplete`, `source_documents_not_ready`). Повтор бесполезен, а
 * заморозка в очереди прятала бы карточку машины (локальная приёмка держит
 * документы «привязанными») и съедалась бы TerminalConflictResolver'ом у
 * приёмки после 2 Этапа. Поэтому операция ЦЕЛИКОМ переезжает в
 * [QuarantinedOperationEntity].
 *
 * Главное правило: ни одна работа инспектора не удаляется, пока она не
 * сохранена в восстановимом виде.
 *  1. Фото КОПИРУЮТСЯ в собственный каталог записи — до любых удалений. Кадр
 *     берётся из подготовленного файла, а если его ещё нет — из исходника:
 *     подготовка пишет файл ДО удаления исходника, поэтому хотя бы один из
 *     двух существует в любой момент.
 *  2. Одной транзакцией: сверка фото со снимком, запись карантина, снятие
 *     очереди сущности и удаление локальной приёмки (каскадом позиции и строки
 *     фото). Упала транзакция или не скопировался существующий файл — копии
 *     удаляются, оригиналы и очередь остаются как были.
 *  3. Только после коммита удаляются оригиналы фото: копии уже принадлежат
 *     карантину.
 *
 * Автоматически из карантина ничего не уходит. «Оформить заново» ([reissue])
 * — по нажатию человека и только когда машину не оформил другой планшет:
 * создаёт черновик 1 Этапа из снимка с КОПИЯМИ фото 1 Этапа. Данные и фото
 * 2 Этапа остаются в записи.
 */
class GroupQuarantine(
    private val port: GroupQuarantinePort,
    /** filesDir приложения: каталоги карантина и operation_photos живут в нём. */
    private val filesDir: File,
    /** Запись черновика 1 Этапа — Stage1DraftRepository.upsert (со слиянием по машине). */
    private val saveDraft: suspend (Stage1DraftState) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    fun observeAll(): Flow<List<QuarantinedOperationEntity>> = port.observeAll()

    sealed interface Outcome {
        /** [lostPhotos] — кадры, файлов которых на планшете уже не было. */
        data class Quarantined(val id: String, val photos: Int, val lostPhotos: Int) : Outcome
    }

    /**
     * Убрать операцию из очереди в карантин. Бросает исключение, если записать
     * снимок не удалось, — тогда очередь и данные остаются нетронутыми, и
     * вызывающий держит мутацию замороженной.
     */
    suspend fun quarantineDelivery(
        mutation: MutationEntity,
        conflict: MutationFailure.GroupConflict,
    ): Outcome {
        val aggregate = port.deliveryAggregate(mutation.entityId)
        val payload = mutation.payloadJson
            ?.let { runCatching { JSON.decodeFromString(DeliveryUpsertRequest.serializer(), it) }.getOrNull() }
            ?: aggregate?.let { it.delivery.toUpsertRequest(it.items) }
            ?: error("нет ни тела запроса, ни локальной приёмки ${mutation.entityId}")

        val id = newId()
        val dir = File(File(filesDir, DIR), id)
        val photos = aggregate?.photos.orEmpty()
        val copied: List<QuarantinedPhoto>
        try {
            dir.mkdirs()
            copied = photos.map { p -> QuarantinedPhoto.of(p, copyFirstExisting(p, dir)) }
            val snapshot = GroupQuarantineSnapshot(
                payload = payload,
                vehicleTypeCode = aggregate?.vehicleTypeCode,
                photos = copied,
            )
            port.commit(
                expectedPhotos = photos,
                row = QuarantinedOperationEntity(
                    id = id,
                    entityType = ENTITY_DELIVERY,
                    entityId = mutation.entityId,
                    reason = conflict.code,
                    claimedAt = conflict.claimedAt,
                    siteId = payload.siteId,
                    groupId = payload.groupId,
                    sourceDocumentIdsJson = JSON.encodeToString(ID_LIST, payload.sourceDocumentIds),
                    vehiclePlate = payload.vehiclePlate,
                    statusCode = payload.statusCode,
                    photoCount = copied.count { it.file != null },
                    snapshotJson = JSON.encodeToString(GroupQuarantineSnapshot.serializer(), snapshot),
                    reissuedAt = null,
                    createdAt = now(),
                ),
            )
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }

        // Оригиналы — только после коммита: копии уже у карантина. Исходник
        // кадра может делить другая строка (тот же кадр у отгрузки) — его
        // оставляем, пока он кому-то нужен.
        for (p in photos) {
            p.localBlobPath?.let(::deleteQuietly)
            p.localThumbPath?.let(::deleteQuietly)
            p.sourcePath?.let { source ->
                if (!runCatching { port.sourceStillUsed(source) }.getOrDefault(true)) deleteQuietly(source)
            }
        }
        return Outcome.Quarantined(
            id = id,
            photos = copied.count { it.file != null },
            lostPhotos = copied.count { it.file == null },
        )
    }

    sealed interface ReissueResult {
        data class Created(val draftId: String) : ReissueResult
        data object NotFound : ReissueResult
        /** Машину оформил другой планшет — оформлять заново нечего. */
        data object NotAllowed : ReissueResult
        data object AlreadyReissued : ReissueResult
    }

    /**
     * «Оформить заново»: черновик 1 Этапа машины из снимка. Фото 1 Этапа
     * КОПИРУЮТСЯ в operation_photos — черновик владеет своими файлами, запись
     * карантина остаётся целой (в ней 2 Этап).
     *
     * Состав и версия машины в черновике — из снимка, то есть заведомо
     * устаревшие: форма при открытии сверит их с актуальными и подтянет новый
     * состав сама (как при любом «состав машины изменился»).
     */
    suspend fun reissue(id: String): ReissueResult {
        val row = port.find(id) ?: return ReissueResult.NotFound
        if (!canReissue(row.reason)) return ReissueResult.NotAllowed
        if (row.reissuedAt != null) return ReissueResult.AlreadyReissued
        val snapshot = JSON.decodeFromString(GroupQuarantineSnapshot.serializer(), row.snapshotJson)
        val dir = File(File(filesDir, DIR), id)
        val opDir = File(filesDir, OPERATION_PHOTOS_DIR)

        val createdFiles = mutableListOf<File>()
        try {
            fun copiesOf(kinds: Set<String>): List<String> = snapshot.photos
                .filter { it.stage == STAGE_BEFORE && it.kind in kinds && it.file != null }
                .mapNotNull { p ->
                    val from = File(dir, p.file!!)
                    if (!from.isFile) return@mapNotNull null
                    val to = createUniquePhotoFile(opDir, "op")
                    createdFiles += to
                    from.copyTo(to, overwrite = true)
                    to.absolutePath
                }

            val draft = draftFromSnapshot(
                snapshot = snapshot,
                draftId = newId(),
                documentPhotoPaths = copiesOf(setOf(KIND_DOCUMENT)),
                cargoPhotoPaths = copiesOf(CARGO_KINDS),
                at = now(),
            )
            saveDraft(draft)
            port.markReissued(id, now())
            return ReissueResult.Created(draft.localDraftId)
        } catch (e: Throwable) {
            createdFiles.forEach { it.delete() }
            throw e
        }
    }

    /** «Удалить»: запись и все её файлы. Только по явному действию человека. */
    suspend fun delete(id: String) {
        port.delete(id)
        File(File(filesDir, DIR), id).deleteRecursively()
    }

    /**
     * Первый файл кадра, который удалось скопировать: подготовленный JPEG,
     * затем исходник. null — на планшете не осталось ни одного.
     *
     * Файл есть, а скопировать его не вышло (нет места, ошибка чтения, исходник
     * в эту секунду удалила подготовка) — это НЕ потерянный кадр: бросаем
     * [RetryLaterException] до записи карантина, оригиналы и очередь целы.
     * Раньше такой кадр молча считался потерянным, и после коммита оригинал
     * удалялся.
     */
    private fun copyFirstExisting(photo: RemoteDeliveryPhotoEntity, dir: File): String? {
        val name = "${photo.id}.jpg"
        var failure: Exception? = null
        for (path in listOfNotNull(photo.localBlobPath, photo.sourcePath)) {
            val from = File(path)
            if (!from.isFile) continue
            try {
                from.copyTo(File(dir, name), overwrite = true)
                return name
            } catch (e: Exception) {
                failure = e
            }
        }
        if (failure != null) throw RetryLaterException("кадр ${photo.id} не скопирован", failure)
        return null
    }

    private fun deleteQuietly(path: String) {
        runCatching { File(path).delete() }
    }

    /**
     * Карантин не записан, но повтор может удаться: кадр не скопировался или
     * фото приёмки изменились между снимком и записью. Ничего не удалено —
     * очередь повторит операцию по backoff (сервер снова ответит 409), а не
     * заморозит её навсегда.
     */
    class RetryLaterException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        /**
         * Совпадают ли файлы кадров со снимком: id и пути. Статус загрузки не
         * важен — копируются файлы; важно, чтобы каскад не унёс кадр, которого
         * нет в снимке, и чтобы подготовка не подменила файл после копии.
         */
        fun samePhotoFiles(
            current: List<RemoteDeliveryPhotoEntity>,
            snapshot: List<RemoteDeliveryPhotoEntity>,
        ): Boolean {
            fun key(p: RemoteDeliveryPhotoEntity) = Triple(p.id, p.localBlobPath, p.sourcePath)
            return current.map(::key).toSet() == snapshot.map(::key).toSet()
        }

        const val DIR = "group_quarantine"
        const val ENTITY_DELIVERY = "delivery"
        private const val OPERATION_PHOTOS_DIR = "operation_photos"
        private const val STAGE_BEFORE = "before"
        private const val KIND_DOCUMENT = "document"
        /** Всё, что не документ, форма 1 Этапа держит в «фото машины». */
        private val CARGO_KINDS = setOf("cargo", "vehicle", "other")

        internal val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val ID_LIST = ListSerializer(String.serializer())

        /** Оформить заново можно всё, кроме машины, которую уже оформили. */
        fun canReissue(reason: String): Boolean = reason != "group_claimed"
    }
}

/**
 * Черновик 1 Этапа из снимка карантина. Чистая функция — проверяется тестом.
 *
 * Тексты достаются из комментария по тем же маркерам, что пишет finalizeStage1
 * («1 Этап: "…"», «Примечание: …»); строка «2 Этап» в черновик 1 Этапа не
 * попадает — она остаётся в записи карантина.
 */
internal fun draftFromSnapshot(
    snapshot: GroupQuarantineSnapshot,
    draftId: String,
    documentPhotoPaths: List<String>,
    cargoPhotoPaths: List<String>,
    at: Long,
): Stage1DraftState {
    val p = snapshot.payload
    val lines = p.comment.orEmpty().lines().map(String::trim)
    val stage1 = lines.firstNotNullOfOrNull { STAGE1_COMMENT.find(it)?.groupValues?.get(1) }.orEmpty()
    val note = lines.firstNotNullOfOrNull { NOTE_COMMENT.find(it)?.groupValues?.get(1)?.trim() }.orEmpty()
    return Stage1DraftState(
        localDraftId = draftId,
        updId = p.sourceDocumentIds.firstOrNull(),
        groupId = p.groupId,
        loadedDocIds = p.sourceDocumentIds,
        loadedGroupRevision = p.groupRevision,
        documentPhotoPaths = documentPhotoPaths,
        cargoPhotoPaths = cargoPhotoPaths,
        vehicleTypeCode = snapshot.vehicleTypeCode,
        materials = p.items.map {
            MaterialDraft(
                name = it.nameRaw,
                qty = it.qtyActual.orEmpty(),
                unit = it.unit,
                price = it.price,
                vatRate = it.vatRate,
                vatSum = it.vatSum,
                sourceDocumentId = it.sourceDocumentId,
                sourceDocumentItemId = it.sourceDocumentItemId,
            )
        },
        commentText = stage1,
        licensePlate = p.vehiclePlate.orEmpty(),
        manualUpdText = note,
        inTransit = p.inTransit,
        isAssets = p.isAssets,
        createdAt = at,
        updatedAt = at,
    )
}

private val STAGE1_COMMENT = Regex("^1 Этап:\\s*\"(.*)\"$")
private val NOTE_COMMENT = Regex("^Примечание:\\s*(.+)$")

/** Полный снимок операции в карантине. */
@Serializable
data class GroupQuarantineSnapshot(
    val schema: Int = 1,
    /** Ровно то, что ушло бы на сервер (тело upsert из очереди). */
    val payload: DeliveryUpsertRequest,
    /** Тип машины — живёт только на планшете (delivery_local_meta). */
    val vehicleTypeCode: String? = null,
    val photos: List<QuarantinedPhoto> = emptyList(),
)

@Serializable
data class QuarantinedPhoto(
    val id: String,
    val kind: String,
    val stage: String,
    val takenAt: String,
    val contentType: String,
    /** Имя копии в каталоге записи; null — файла на планшете уже не было. */
    val file: String?,
) {
    companion object {
        fun of(p: RemoteDeliveryPhotoEntity, file: String?) = QuarantinedPhoto(
            id = p.id,
            kind = p.kind,
            stage = p.stage,
            takenAt = p.takenAt,
            contentType = p.contentType,
            file = file,
        )
    }
}

/** Локальная приёмка целиком — то, что уходит в снимок. */
data class DeliveryAggregate(
    val delivery: RemoteDeliveryEntity,
    val items: List<RemoteDeliveryItemEntity>,
    val photos: List<RemoteDeliveryPhotoEntity>,
    val vehicleTypeCode: String?,
)

/**
 * Узкий шов к Room: тесты карантина работают без Android. Прод —
 * [RoomGroupQuarantinePort].
 */
interface GroupQuarantinePort {
    fun observeAll(): Flow<List<QuarantinedOperationEntity>>
    suspend fun deliveryAggregate(deliveryId: String): DeliveryAggregate?

    /**
     * ОДНОЙ транзакцией: вставить запись, снять очередь сущности, удалить
     * локальную приёмку (каскадом позиции, фото, мета).
     *
     * Первым делом внутри транзакции фото приёмки сверяются со снимком
     * [expectedPhotos] ([GroupQuarantine.samePhotoFiles]); расхождение —
     * [GroupQuarantine.RetryLaterException] до любых изменений. Иначе кадр,
     * снятый или подготовленный после снимка, ушёл бы каскадом без копии.
     */
    suspend fun commit(row: QuarantinedOperationEntity, expectedPhotos: List<RemoteDeliveryPhotoEntity>)
    suspend fun find(id: String): QuarantinedOperationEntity?
    suspend fun markReissued(id: String, at: Long)
    suspend fun delete(id: String)

    /** Нужен ли исходник кадра другой неподготовленной строке. */
    suspend fun sourceStillUsed(path: String): Boolean
}
