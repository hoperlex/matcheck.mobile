package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.Stage1DraftDao
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Тонкий репозиторий поверх [Stage1DraftDao]: занимается сериализацией
 * списков фото и материалов в JSON-строки (Room хранит примитивы).
 */
class Stage1DraftRepository(private val dao: Stage1DraftDao) {

    fun observeAll(): Flow<List<Stage1DraftEntity>> = dao.observeAll()

    suspend fun findById(id: String): Stage1DraftEntity? = dao.findById(id)

    suspend fun findByUpdId(updId: String): Stage1DraftEntity? = dao.findByUpdId(updId)

    suspend fun findByGroupId(groupId: String): Stage1DraftEntity? = dao.findByGroupId(groupId)

    /**
     * `createdAt` фиксируется на самой первой записи (= момент «Начато»,
     * первое фото) и не меняется при последующих апдейтах state.
     */
    suspend fun upsert(state: Stage1DraftState) {
        val existing = dao.findById(state.localDraftId)
        val stable = if (existing != null) state.copy(createdAt = existing.createdAt) else state
        dao.upsert(stable.toEntity())
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    fun toState(entity: Stage1DraftEntity): Stage1DraftState = Stage1DraftState(
        localDraftId = entity.localDraftId,
        updId = entity.updId,
        groupId = entity.groupId,
        loadedDocIds = decodeStringList(entity.loadedDocIdsJson),
        loadedGroupRevision = entity.loadedGroupRevision,
        documentPhotoPaths = decodeStringList(entity.documentPhotoPathsJson),
        cargoPhotoPaths = decodeStringList(entity.cargoPhotoPathsJson),
        vehicleTypeCode = entity.vehicleTypeCode,
        materials = decodeMaterials(entity.materialsJson),
        commentText = entity.commentText,
        licensePlate = entity.licensePlate,
        manualUpdText = entity.manualUpdText,
        inTransit = entity.inTransit,
        isAssets = entity.isAssets,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    private fun Stage1DraftState.toEntity(): Stage1DraftEntity = Stage1DraftEntity(
        localDraftId = localDraftId,
        updId = updId,
        groupId = groupId,
        loadedDocIdsJson = encodeStringList(loadedDocIds),
        loadedGroupRevision = loadedGroupRevision,
        documentPhotoPathsJson = encodeStringList(documentPhotoPaths),
        cargoPhotoPathsJson = encodeStringList(cargoPhotoPaths),
        vehicleTypeCode = vehicleTypeCode,
        materialsJson = encodeMaterials(materials),
        commentText = commentText,
        licensePlate = licensePlate,
        manualUpdText = manualUpdText,
        inTransit = inTransit,
        isAssets = isAssets,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(stringListSerializer, values)

    private fun decodeStringList(raw: String): List<String> = runCatching {
        json.decodeFromString(stringListSerializer, raw)
    }.getOrDefault(emptyList())

    private fun encodeMaterials(values: List<MaterialDraft>): String {
        val wire = values.map {
            MaterialDraftWire(
                name = it.name, qty = it.qty, unit = it.unit,
                id = it.id, price = it.price, vatRate = it.vatRate, vatSum = it.vatSum,
                sourceDocumentId = it.sourceDocumentId,
                sourceDocumentItemId = it.sourceDocumentItemId,
            )
        }
        return json.encodeToString(materialListSerializer, wire)
    }

    private fun decodeMaterials(raw: String): List<MaterialDraft> = runCatching {
        json.decodeFromString(materialListSerializer, raw)
            .map {
                MaterialDraft(
                    name = it.name, qty = it.qty, unit = it.unit,
                    id = it.id, price = it.price, vatRate = it.vatRate, vatSum = it.vatSum,
                    sourceDocumentId = it.sourceDocumentId,
                    sourceDocumentItemId = it.sourceDocumentItemId,
                )
            }
    }.getOrDefault(emptyList())

    @Serializable
    private data class MaterialDraftWire(
        val name: String,
        val qty: String,
        val unit: String = "",
        val id: String? = null,
        val price: String? = null,
        val vatRate: String? = null,
        val vatSum: String? = null,
        /**
         * Происхождение позиции. Default null обязателен: черновики,
         * записанные до появления полей, декодируются этим же
         * сериализатором и не должны падать.
         */
        val sourceDocumentId: String? = null,
        val sourceDocumentItemId: String? = null,
    )

    companion object {
        private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val stringListSerializer = ListSerializer(String.serializer())
        private val materialListSerializer = ListSerializer(MaterialDraftWire.serializer())
    }
}

/**
 * Удобное представление черновика для VM: фото — списком путей, материалы —
 * списком MaterialDraft (как в форме). Конвертация в [Stage1DraftEntity]
 * и обратно — внутри [Stage1DraftRepository].
 */
// Транзит хранится в state класса ниже как новое поле — см. Stage1DraftState.inTransit.

data class Stage1DraftState(
    val localDraftId: String,
    val updId: String?,
    /**
     * «Машина» — id корневого пакета. null у черновика по одиночному
     * документу, у пустой приёмки и у черновиков, начатых до появления
     * группировки: последние форма подхватывает по updId и дозаполняет.
     */
    val groupId: String? = null,
    /** Состав машины, по которому в форму загружены позиции. */
    val loadedDocIds: List<String> = emptyList(),
    /** Версия состава на тот же момент. Сверяется при финализации. */
    val loadedGroupRevision: Int? = null,
    val documentPhotoPaths: List<String>,
    val cargoPhotoPaths: List<String>,
    val vehicleTypeCode: String?,
    val materials: List<MaterialDraft>,
    val commentText: String,
    val licensePlate: String,
    val manualUpdText: String,
    /** Транзит — чекбокс на 1 этапе. Сохраняется в draft, default false. */
    val inTransit: Boolean = false,
    /** ОС — чекбокс «основные средства» на 1 этапе. Default false. */
    val isAssets: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
