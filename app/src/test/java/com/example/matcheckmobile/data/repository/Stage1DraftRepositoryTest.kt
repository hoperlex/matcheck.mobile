package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.Stage1DraftDao
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.viewmodel.DraftPlan
import com.example.matcheckmobile.presentation.viewmodel.DraftVariant
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

        val repo = Stage1DraftRepository(FakeStage1DraftDao(), DirectTx)
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
        val repo = Stage1DraftRepository(FakeStage1DraftDao(), DirectTx)

        val state = repo.toState(entity(materialsJson = "{не json"))

        assertEquals(emptyList<MaterialDraft>(), state.materials)
    }

    @Test
    fun `происхождение позиций переживает круг записи и чтения`() = runBlocking {
        val dao = FakeStage1DraftDao()
        val repo = Stage1DraftRepository(dao, DirectTx)
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

    @Test
    fun `черновик до миграции 26-27 читается без отложенных строк и вариантов`() = runBlocking {
        val repo = Stage1DraftRepository(FakeStage1DraftDao(), DirectTx)

        val state = repo.toState(entity(materialsJson = "[]"))

        assertEquals(emptyList<MaterialDraft>(), state.parkedMaterials)
        assertEquals(emptyList<DraftVariant>(), state.mergedVariants)
    }

    @Test
    fun `отложенные строки и варианты переживают круг записи и чтения`() = runBlocking {
        val dao = FakeStage1DraftDao()
        val repo = Stage1DraftRepository(dao, DirectTx)
        val parked = MaterialDraft(name = "Труба", qty = "3", unit = "м", sourceDocumentId = "upd-9", sourceDocumentItemId = "i-1")
        val variant = DraftVariant(draftId = "d-2", updatedAt = 5L, licensePlate = "В777ВВ", chosen = true)

        repo.upsert(state("draft-1").copy(parkedMaterials = listOf(parked), mergedVariants = listOf(variant)))
        val restored = repo.toState(dao.rows.getValue("draft-1"))

        assertEquals(listOf(parked), restored.parkedMaterials)
        assertEquals(listOf(variant), restored.mergedVariants)
    }

    @Test
    fun `план слияния — поглощённые удалены, результат записан с исходным временем начала`() = runBlocking {
        val dao = FakeStage1DraftDao()
        val repo = Stage1DraftRepository(dao, DirectTx)
        repo.upsert(state("a").copy(createdAt = 10L))
        repo.upsert(state("b"))

        repo.applyPlan(DraftPlan(result = null, delete = listOf("b"), save = listOf(state("a").copy(createdAt = 99L, licensePlate = "X"))))

        assertEquals(setOf("a"), dao.rows.keys)
        assertEquals(10L, dao.rows.getValue("a").createdAt)
        assertEquals("X", dao.rows.getValue("a").licensePlate)
    }

    @Test
    fun `документ удалён окончательно — его строки убраны из всех черновиков`() = runBlocking {
        val dao = FakeStage1DraftDao()
        val repo = Stage1DraftRepository(dao, DirectTx)
        val gone = MaterialDraft(name = "Песок", qty = "1", sourceDocumentId = "upd-gone", sourceDocumentItemId = "i")
        val kept = MaterialDraft(name = "Щебень", qty = "2", sourceDocumentId = "upd-1", sourceDocumentItemId = "j")
        val own = MaterialDraft(name = "Поддон", qty = "1")
        repo.upsert(state("a").copy(materials = listOf(gone, kept, own)))
        repo.upsert(state("b").copy(parkedMaterials = listOf(gone)))
        repo.upsert(state("c").copy(materials = listOf(kept)))

        val changed = repo.dropRowsOfDocuments(listOf("upd-gone"))

        assertEquals(2, changed)
        assertEquals(listOf(kept, own), repo.toState(dao.rows.getValue("a")).materials)
        assertEquals(emptyList<MaterialDraft>(), repo.toState(dao.rows.getValue("b")).parkedMaterials)
    }

    private fun state(id: String) = Stage1DraftState(
        localDraftId = id,
        updId = "upd-1",
        documentPhotoPaths = emptyList(),
        cargoPhotoPaths = emptyList(),
        vehicleTypeCode = null,
        materials = emptyList(),
        commentText = "",
        licensePlate = "",
        manualUpdText = "",
        createdAt = 1L,
        updatedAt = 1L,
    )

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

    private object DirectTx : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    /** Room тут не нужен: проверяется (де)сериализация и логика репозитория. */
    private class FakeStage1DraftDao : Stage1DraftDao {
        val rows = LinkedHashMap<String, Stage1DraftEntity>()
        var stored: Stage1DraftEntity? = null

        override suspend fun findById(id: String): Stage1DraftEntity? = rows[id]

        override suspend fun findByUpdId(updId: String): Stage1DraftEntity? =
            rows.values.firstOrNull { it.updId == updId }

        override suspend fun findByGroupId(groupId: String): Stage1DraftEntity? =
            rows.values.firstOrNull { it.groupId == groupId }

        override fun observeAll(): Flow<List<Stage1DraftEntity>> = flowOf(rows.values.toList())

        override suspend fun findAll(): List<Stage1DraftEntity> = rows.values.toList()

        override suspend fun upsert(draft: Stage1DraftEntity) {
            rows[draft.localDraftId] = draft
            stored = draft
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
            if (stored?.localDraftId == id) stored = null
        }

        override suspend fun count(): Int = rows.size
    }
}
