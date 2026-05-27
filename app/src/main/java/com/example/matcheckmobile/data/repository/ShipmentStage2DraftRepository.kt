package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.ShipmentStage2DraftDao
import com.example.matcheckmobile.data.local.entity.ShipmentStage2DraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Зеркало [Stage2DraftRepository] для отгрузки. */
class ShipmentStage2DraftRepository(private val dao: ShipmentStage2DraftDao) {

    fun observeAll(): Flow<List<ShipmentStage2DraftEntity>> = dao.observeAll()

    fun observeIds(): Flow<List<String>> = dao.observeIds()

    suspend fun findById(id: String): ShipmentStage2DraftEntity? = dao.findById(id)

    suspend fun upsert(state: ShipmentStage2DraftState) {
        val existing = dao.findById(state.shipmentId)
        val stable = if (existing != null) state.copy(createdAt = existing.createdAt) else state
        dao.upsert(stable.toEntity())
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    fun toState(entity: ShipmentStage2DraftEntity): ShipmentStage2DraftState = ShipmentStage2DraftState(
        shipmentId = entity.shipmentId,
        documentPhotoPaths = decodeStringList(entity.documentPhotoPathsJson),
        vehiclePhotoPaths = decodeStringList(entity.vehiclePhotoPathsJson),
        vehicleTypeCode = entity.vehicleTypeCode,
        materials = decodeMaterials(entity.materialsJson),
        editedIndexes = decodeIntList(entity.editedIndexesJson).toSet(),
        commentText = entity.commentText,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    private fun ShipmentStage2DraftState.toEntity(): ShipmentStage2DraftEntity = ShipmentStage2DraftEntity(
        shipmentId = shipmentId,
        documentPhotoPathsJson = encodeStringList(documentPhotoPaths),
        vehiclePhotoPathsJson = encodeStringList(vehiclePhotoPaths),
        vehicleTypeCode = vehicleTypeCode,
        materialsJson = encodeMaterials(materials),
        editedIndexesJson = encodeIntList(editedIndexes.toList()),
        commentText = commentText,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun encodeStringList(v: List<String>) = json.encodeToString(stringListSer, v)
    private fun decodeStringList(raw: String): List<String> =
        runCatching { json.decodeFromString(stringListSer, raw) }.getOrDefault(emptyList())
    private fun encodeIntList(v: List<Int>) = json.encodeToString(intListSer, v)
    private fun decodeIntList(raw: String): List<Int> =
        runCatching { json.decodeFromString(intListSer, raw) }.getOrDefault(emptyList())

    private fun encodeMaterials(values: List<MaterialDraft>): String {
        val wire = values.map {
            MaterialDraftWire(
                name = it.name, qty = it.qty, unit = it.unit,
                id = it.id, price = it.price, vatRate = it.vatRate, vatSum = it.vatSum,
            )
        }
        return json.encodeToString(materialListSer, wire)
    }

    private fun decodeMaterials(raw: String): List<MaterialDraft> = runCatching {
        json.decodeFromString(materialListSer, raw).map {
            MaterialDraft(
                name = it.name, qty = it.qty, unit = it.unit,
                id = it.id, price = it.price, vatRate = it.vatRate, vatSum = it.vatSum,
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
    )

    companion object {
        private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val stringListSer = ListSerializer(String.serializer())
        private val intListSer = ListSerializer(Int.serializer())
        private val materialListSer = ListSerializer(MaterialDraftWire.serializer())
    }
}

data class ShipmentStage2DraftState(
    val shipmentId: String,
    val documentPhotoPaths: List<String>,
    val vehiclePhotoPaths: List<String>,
    val vehicleTypeCode: String?,
    val materials: List<MaterialDraft>,
    val editedIndexes: Set<Int>,
    val commentText: String,
    val createdAt: Long,
    val updatedAt: Long,
)
