package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.Stage2DraftDao
import com.example.matcheckmobile.data.local.entity.Stage2DraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Репозиторий поверх [Stage2DraftDao] с JSON-сериализацией фото-списков,
 * материалов и индексов правок. `createdAt` стабилизируется при upsert
 * (берётся из существующей записи, если она уже есть).
 */
class Stage2DraftRepository(private val dao: Stage2DraftDao) {

    fun observeAll(): Flow<List<Stage2DraftEntity>> = dao.observeAll()

    fun observeIds(): Flow<List<String>> = dao.observeIds()

    suspend fun findById(id: String): Stage2DraftEntity? = dao.findById(id)

    suspend fun upsert(state: Stage2DraftState) {
        val existing = dao.findById(state.deliveryId)
        val stable = if (existing != null) state.copy(createdAt = existing.createdAt) else state
        dao.upsert(stable.toEntity())
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    fun toState(entity: Stage2DraftEntity): Stage2DraftState = Stage2DraftState(
        deliveryId = entity.deliveryId,
        documentPhotoPaths = decodeStringList(entity.documentPhotoPathsJson),
        vehiclePhotoPaths = decodeStringList(entity.vehiclePhotoPathsJson),
        vehicleTypeCode = entity.vehicleTypeCode,
        materials = decodeMaterials(entity.materialsJson),
        editedIndexes = decodeIntList(entity.editedIndexesJson).toSet(),
        commentText = entity.commentText,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    private fun Stage2DraftState.toEntity(): Stage2DraftEntity = Stage2DraftEntity(
        deliveryId = deliveryId,
        documentPhotoPathsJson = encodeStringList(documentPhotoPaths),
        vehiclePhotoPathsJson = encodeStringList(vehiclePhotoPaths),
        vehicleTypeCode = vehicleTypeCode,
        materialsJson = encodeMaterials(materials),
        editedIndexesJson = encodeIntList(editedIndexes.toList()),
        commentText = commentText,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun encodeStringList(values: List<String>): String =
        json.encodeToString(stringListSerializer, values)

    private fun decodeStringList(raw: String): List<String> = runCatching {
        json.decodeFromString(stringListSerializer, raw)
    }.getOrDefault(emptyList())

    private fun encodeIntList(values: List<Int>): String =
        json.encodeToString(intListSerializer, values)

    private fun decodeIntList(raw: String): List<Int> = runCatching {
        json.decodeFromString(intListSerializer, raw)
    }.getOrDefault(emptyList())

    private fun encodeMaterials(values: List<MaterialDraft>): String {
        val wire = values.map { MaterialDraftWire(name = it.name, qty = it.qty, unit = it.unit) }
        return json.encodeToString(materialListSerializer, wire)
    }

    private fun decodeMaterials(raw: String): List<MaterialDraft> = runCatching {
        json.decodeFromString(materialListSerializer, raw)
            .map { MaterialDraft(name = it.name, qty = it.qty, unit = it.unit) }
    }.getOrDefault(emptyList())

    @Serializable
    private data class MaterialDraftWire(
        val name: String,
        val qty: String,
        val unit: String = "",
    )

    companion object {
        private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val stringListSerializer = ListSerializer(String.serializer())
        private val intListSerializer = ListSerializer(Int.serializer())
        private val materialListSerializer = ListSerializer(MaterialDraftWire.serializer())
    }
}

data class Stage2DraftState(
    val deliveryId: String,
    val documentPhotoPaths: List<String>,
    val vehiclePhotoPaths: List<String>,
    val vehicleTypeCode: String?,
    val materials: List<MaterialDraft>,
    val editedIndexes: Set<Int>,
    val commentText: String,
    val createdAt: Long,
    val updatedAt: Long,
)
