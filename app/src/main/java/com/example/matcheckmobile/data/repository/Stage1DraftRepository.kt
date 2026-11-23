package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.Stage1DraftDao
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.viewmodel.DraftPlan
import com.example.matcheckmobile.presentation.viewmodel.DraftVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Тонкий репозиторий поверх [Stage1DraftDao]: занимается сериализацией
 * списков фото и материалов в JSON-строки (Room хранит примитивы).
 */
class Stage1DraftRepository(
    private val dao: Stage1DraftDao,
    /** Слияние черновиков машины пишется одной транзакцией — см. [applyPlan]. */
    private val tx: TransactionRunner,
) {

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

    /** Все черновики 1 Этапа на планшете. */
    suspend fun listAll(): List<Stage1DraftState> = dao.findAll().map(::toState)

    /**
     * План слияния черновиков машины ([com.example.matcheckmobile.presentation.viewmodel.planMachineDrafts])
     * — ОДНОЙ транзакцией: сначала поглощённые удаляются, затем пишется
     * результат. Порознь смерть процесса между шагами оставила бы либо две
     * копии одной работы, либо ни одной.
     */
    suspend fun applyPlan(plan: DraftPlan) = tx.run {
        plan.delete.forEach { dao.deleteById(it) }
        plan.save.forEach { state ->
            val existing = dao.findById(state.localDraftId)
            val stable = if (existing != null) state.copy(createdAt = existing.createdAt) else state
            dao.upsert(stable.toEntity())
        }
    }

    /**
     * Сервер сообщил, что документы удалены окончательно (`deletedReasons` в
     * /sync = `deleted`): их строки больше некуда возвращать — убираем из
     * материалов и из отложенных во всех черновиках. Для СКРЫТЫХ документов
     * (`hidden`) не вызывается: их строки ждут возвращения документа.
     *
     * @return сколько черновиков изменилось.
     */
    suspend fun dropRowsOfDocuments(documentIds: Collection<String>): Int {
        if (documentIds.isEmpty()) return 0
        val ids = documentIds.toSet()
        return tx.run {
            var changed = 0
            for (entity in dao.findAll()) {
                val state = toState(entity)
                val materials = state.materials.filterNot { it.sourceDocumentId in ids }
                val parked = state.parkedMaterials.filterNot { it.sourceDocumentId in ids }
                if (materials.size == state.materials.size && parked.size == state.parkedMaterials.size) continue
                dao.upsert(state.copy(materials = materials, parkedMaterials = parked).toEntity())
                changed++
            }
            changed
        }
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
        parkedMaterials = decodeMaterials(entity.parkedMaterialsJson),
        mergedVariants = decodeVariants(entity.mergedVariantsJson),
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
        parkedMaterialsJson = encodeMaterials(parkedMaterials),
        mergedVariantsJson = json.encodeToString(variantListSerializer, mergedVariants),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun decodeVariants(raw: String): List<DraftVariant> = runCatching {
        json.decodeFromString(variantListSerializer, raw)
    }.getOrDefault(emptyList())

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
        private val variantListSerializer = ListSerializer(DraftVariant.serializer())
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
    /**
     * Строки документов, которых сейчас нет в локальной базе (временно скрыты
     * сервером). В форму и отправку не идут; см. Stage1DraftEntity.parkedMaterialsJson.
     */
    val parkedMaterials: List<MaterialDraft> = emptyList(),
    /** Варианты полей после слияния черновиков машины; см. [DraftVariant]. */
    val mergedVariants: List<DraftVariant> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
