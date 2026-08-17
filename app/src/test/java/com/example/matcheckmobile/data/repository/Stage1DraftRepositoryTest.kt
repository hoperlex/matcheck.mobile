package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.Stage1DraftDao
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сериализация черновика 1 Этапа.
 *
 * Главное, что здесь ловится: черновик, записанный ПРЕДЫДУЩЕЙ версией
 * приложения, обязан читаться после обновления. У позиций появились поля
 * происхождения, а в materialsJson на планшете их нет — падение декодера
 * стёрло бы уже введённые инспектором материалы и снятые фото.
 */
class Stage1DraftRepositoryTest {

    @Test
    fun `старый materialsJson без полей происхождения читается`() = runBlocking {
        // Ровно тот формат, что писала версия до появления группировки.
        val legacyJson = """
            [{"name":"Арматура","qty":"12","unit":"т","id":null,"price":"100",
              "vatRate":"20","vatSum":"20"}]
        """.trimIndent().replace("\n", "")

        val repo = Stage1DraftRepository(FakeStage1DraftDao())
        val state = repo.toState(entity(materialsJson = legacyJson))

        assertEquals(1, state.materials.size)
        val m = state.materials.first()
        assertEquals("Арматура", m.name)
        assertEquals("100", m.price)
        // Происхождения у старой записи нет — это null, а не падение декодера.
        assertNull(m.sourceDocumentId)
        assertNull(m.sourceDocumentItemId)
    }

    @Test
    fun `битый materialsJson не роняет восстановление черновика`() = runBlocking {
        val repo = Stage1DraftRepository(FakeStage1DraftDao())

        val state = repo.toState(entity(materialsJson = "{не json"))

        assertEquals(emptyList<MaterialDraft>(), state.materials)
    }

    @Test
    fun `происхождение позиций переживает круг записи и чтения`() = runBlocking {
        val dao = FakeStage1DraftDao()
        val repo = Stage1DraftRepository(dao)
        val original = Stage1DraftState(
            localDraftId = "draft-1",
            updId = "upd-1",
            groupId = "bundle-1",
            loadedDocIds = listOf("upd-1", "upd-2"),
            loadedGroupRevision = 3,
            documentPhotoPaths = emptyList(),
            cargoPhotoPaths = listOf("/photo.jpg"),
            vehicleTypeCode = null,
            materials = listOf(
                MaterialDraft(
                    name = "Арматура",
                    qty = "12",
                    unit = "т",
                    sourceDocumentId = "upd-2",
                    sourceDocumentItemId = "item-7",
                ),
                // Строка, добавленная инспектором руками: происхождения нет.
                MaterialDraft(name = "Поддон", qty = "1", unit = "шт"),
            ),
            commentText = "",
            licensePlate = "А123АА",
            manualUpdText = "",
            createdAt = 1L,
            updatedAt = 1L,
        )

        repo.upsert(original)
        val restored = repo.toState(dao.stored!!)

        assertEquals("bundle-1", restored.groupId)
        assertEquals(listOf("upd-1", "upd-2"), restored.loadedDocIds)
        assertEquals(3, restored.loadedGroupRevision)
        assertEquals(listOf("upd-2", null), restored.materials.map { it.sourceDocumentId })
        assertEquals(listOf("item-7", null), restored.materials.map { it.sourceDocumentItemId })
    }

    private fun entity(materialsJson: String) = Stage1DraftEntity(
        localDraftId = "draft-1",
        updId = "upd-1",
        documentPhotoPathsJson = "[]",
        cargoPhotoPathsJson = "[]",
        vehicleTypeCode = null,
        materialsJson = materialsJson,
        commentText = "",
        licensePlate = "",
        manualUpdText = "",
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** Room тут не нужен: проверяется только (де)сериализация репозитория. */
    private class FakeStage1DraftDao : Stage1DraftDao {
        var stored: Stage1DraftEntity? = null

        override suspend fun findById(id: String): Stage1DraftEntity? =
            stored?.takeIf { it.localDraftId == id }

        override suspend fun findByUpdId(updId: String): Stage1DraftEntity? =
            stored?.takeIf { it.updId == updId }

        override suspend fun findByGroupId(groupId: String): Stage1DraftEntity? =
            stored?.takeIf { it.groupId == groupId }

        override fun observeAll(): Flow<List<Stage1DraftEntity>> =
            flowOf(listOfNotNull(stored))

        override suspend fun upsert(draft: Stage1DraftEntity) {
            stored = draft
        }

        override suspend fun deleteById(id: String) {
            if (stored?.localDraftId == id) stored = null
        }

        override suspend fun count(): Int = if (stored != null) 1 else 0
    }
}
