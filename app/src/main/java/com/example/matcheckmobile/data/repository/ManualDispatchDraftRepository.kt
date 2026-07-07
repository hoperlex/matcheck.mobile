package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.ManualDispatchDraftDao
import com.example.matcheckmobile.data.local.entity.ManualDispatchDraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Зеркало [ManualEntryDraftRepository] для «Ручного выноса» (shipment).
 * Дополнительно хранит [ManualDispatchDraftState.shipmentPurpose] («Тип отгрузки»).
 */
class ManualDispatchDraftRepository(private val dao: ManualDispatchDraftDao) {

    fun observeAll(): Flow<List<ManualDispatchDraftEntity>> = dao.observeAll()

    suspend fun findById(id: String): ManualDispatchDraftEntity? = dao.findById(id)

    suspend fun create(siteId: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsert(
            ManualDispatchDraftEntity(
                localDraftId = id,
                siteId = siteId,
                documentPhotoPathsJson = "[]",
                cargoPhotoPathsJson = "[]",
                manualUpdText = "",
                materialsJson = "[]",
                commentText = "",
                shipmentPurpose = null,
                isAssets = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun upsert(state: ManualDispatchDraftState) {
        val existing = dao.findById(state.localDraftId)
        val stable = if (existing != null) state.copy(createdAt = existing.createdAt) else state
        dao.upsert(stable.toEntity())
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    fun toState(entity: ManualDispatchDraftEntity): ManualDispatchDraftState = ManualDispatchDraftState(
        localDraftId = entity.localDraftId,
        siteId = entity.siteId,
        documentPhotoPaths = decodeStringList(entity.documentPhotoPathsJson),
        cargoPhotoPaths = decodeStringList(entity.cargoPhotoPathsJson),
        manualUpdText = entity.manualUpdText,
        materials = decodeMaterials(entity.materialsJson),
        commentText = entity.commentText,
        shipmentPurpose = entity.shipmentPurpose,
        isAssets = entity.isAssets,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    private fun ManualDispatchDraftState.toEntity(): ManualDispatchDraftEntity = ManualDispatchDraftEntity(
        localDraftId = localDraftId,
        siteId = siteId,
        documentPhotoPathsJson = encodeStringList(documentPhotoPaths),
        cargoPhotoPathsJson = encodeStringList(cargoPhotoPaths),
        manualUpdText = manualUpdText,
        materialsJson = encodeMaterials(materials),
        commentText = commentText,
        shipmentPurpose = shipmentPurpose,
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
        private val stringListSerializer = ListSerializer(String.serializer())
        private val materialListSerializer = ListSerializer(MaterialDraftWire.serializer())
    }
}

data class ManualDispatchDraftState(
    val localDraftId: String,
    val siteId: String,
    val documentPhotoPaths: List<String>,
    val cargoPhotoPaths: List<String>,
    val manualUpdText: String,
    val materials: List<MaterialDraft>,
    val commentText: String,
    val shipmentPurpose: String? = null,
    val isAssets: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
