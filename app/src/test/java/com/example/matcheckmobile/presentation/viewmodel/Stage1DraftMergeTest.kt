package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.repository.Stage1DraftState
import com.example.matcheckmobile.domain.model.draftGroupKey
import com.example.matcheckmobile.domain.model.testDoc
import com.example.matcheckmobile.presentation.components.MaterialDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновики 1 Этапа при объединении и разъединении машин на портале.
 *
 * Главный инвариант: работа инспектора не пропадает, а переезжает туда, где
 * её документы сейчас.
 */
class Stage1DraftMergeTest {

    private fun row(doc: String?, item: String?, name: String, qty: String) =
        MaterialDraft(name = name, qty = qty, unit = "шт", sourceDocumentId = doc, sourceDocumentItemId = item)

    private fun draft(
        id: String,
        updId: String?,
        groupId: String?,
        loaded: List<String> = listOfNotNull(updId),
        materials: List<MaterialDraft> = emptyList(),
        parked: List<MaterialDraft> = emptyList(),
        doc: List<String> = emptyList(),
        cargo: List<String> = emptyList(),
        plate: String = "",
        vehicle: String? = null,
        comment: String = "",
        updatedAt: Long = 1L,
        createdAt: Long = 1L,
        inTransit: Boolean = false,
    ) = Stage1DraftState(
        localDraftId = id,
        updId = updId,
        groupId = groupId,
        loadedDocIds = loaded,
        documentPhotoPaths = doc,
        cargoPhotoPaths = cargo,
        vehicleTypeCode = vehicle,
        materials = materials,
        parkedMaterials = parked,
        commentText = comment,
        licensePlate = plate,
        manualUpdText = "",
        inTransit = inTransit,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private var seq = 0
    private fun plan(
        targetKey: String,
        targetDocIds: List<String>,
        drafts: List<Stage1DraftState>,
        docs: List<com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity>,
        preferredId: String? = null,
    ): DraftPlan {
        val byId = docs.associateBy { it.id }
        return planMachineDrafts(
            targetKey = targetKey,
            targetDocs = targetDocIds.map(byId::getValue),
            drafts = drafts,
            docsById = byId,
            preferredId = preferredId,
            newDraftId = { "new-${++seq}" },
            now = 100L,
        )
    }

    // ── Ключ машины по документам ─────────────────────────────────────────

    @Test
    fun `после объединения ключ черновика — новая голова, а не сохранённый groupId`() {
        // Черновик начат по машине B; менеджер объединил B в A.
        val docs = mapOf("b1" to testDoc("b1", groupId = "A"))

        assertEquals("A", draftGroupKey(draftGroupId = "B", draftUpdId = "b1", docsById = docs))
    }

    @Test
    fun `якоря нет в базе — ключ по любому загруженному документу`() {
        val docs = mapOf("b2" to testDoc("b2", groupId = "A"))

        assertEquals(
            "A",
            draftGroupKey(draftGroupId = "B", draftUpdId = "b1", docsById = docs, loadedDocIds = listOf("b1", "b2")),
        )
        // Ни одного документа в базе — сохранённый ключ: черновик не сиротеет.
        assertEquals("B", draftGroupKey(draftGroupId = "B", draftUpdId = "b1", docsById = emptyMap()))
    }

    // ── Объединение ─────────────────────────────────────────────────────────

    @Test
    fun `два черновика объединённой машины сливаются в открытый, фото складываются`() {
        val docs = listOf(
            testDoc("a1", groupId = "A"), testDoc("b1", groupId = "A"),
        )
        val dA = draft(
            "dA", "a1", "A", materials = listOf(row("a1", "ia", "Арматура", "11")),
            cargo = listOf("/a.jpg"), plate = "А111АА", updatedAt = 5L, createdAt = 3L,
        )
        val dB = draft(
            "dB", "b1", "B", materials = listOf(row("b1", "ib", "Труба", "4"), row(null, null, "Поддон", "1")),
            doc = listOf("/b-doc.jpg"), cargo = listOf("/b.jpg"), updatedAt = 7L, createdAt = 2L,
        )

        val p = plan("A", listOf("a1", "b1"), listOf(dA, dB), docs, preferredId = "dA")

        val r = p.result!!
        assertEquals("dA", r.localDraftId)
        assertEquals(listOf("dB"), p.delete)
        assertEquals(listOf("/a.jpg", "/b.jpg"), r.cargoPhotoPaths)
        assertEquals(listOf("/b-doc.jpg"), r.documentPhotoPaths)
        assertEquals(listOf("Арматура", "Труба", "Поддон"), r.materials.map { it.name })
        // Одно значение госномера (у B пусто) — конфликта нет.
        assertEquals("А111АА", r.licensePlate)
        assertTrue(r.mergedVariants.isEmpty())
        assertEquals(setOf("a1", "b1"), r.loadedDocIds.toSet())
        assertEquals(2L, r.createdAt)
        assertEquals("A", r.groupId)
        assertEquals(r, p.save.first())
    }

    @Test
    fun `совпавшая строка берётся из более свежего черновика, одноимённые строки разных УПД не схлопываются`() {
        val docs = listOf(testDoc("a1", groupId = "A"), testDoc("b1", groupId = "A"))
        val dA = draft("dA", "a1", "A", materials = listOf(row("a1", "i1", "Арматура", "10")), updatedAt = 1L)
        val dB = draft(
            "dB", "b1", "B",
            materials = listOf(row("a1", "i1", "Арматура", "12"), row("b1", "i9", "Арматура", "5")),
            updatedAt = 9L,
        )

        val r = plan("A", listOf("a1", "b1"), listOf(dA, dB), docs, preferredId = "dA").result!!

        assertEquals(listOf("12", "5"), r.materials.map { it.qty })
        assertEquals(listOf("a1", "b1"), r.materials.map { it.sourceDocumentId })
    }

    @Test
    fun `разные госномера — конфликт, значение открытого черновика, оба варианта ждут выбора`() {
        val docs = listOf(testDoc("a1", groupId = "A"), testDoc("b1", groupId = "A"))
        val dA = draft("dA", "a1", "A", plate = "А111АА", vehicle = "truck", updatedAt = 1L, inTransit = true)
        val dB = draft("dB", "b1", "B", plate = "В222ВВ", vehicle = "truck", updatedAt = 2L)

        val r = plan("A", listOf("a1", "b1"), listOf(dA, dB), docs, preferredId = "dA").result!!

        assertEquals("А111АА", r.licensePlate)
        assertEquals("truck", r.vehicleTypeCode)
        assertTrue(r.inTransit)
        assertTrue(r.mergedVariants.awaitingChoice())
        assertEquals(setOf("А111АА", "В222ВВ"), r.mergedVariants.map { it.licensePlate }.toSet())

        val chosen = r.chooseVariant("dB")
        assertEquals("В222ВВ", chosen.licensePlate)
        assertFalse(chosen.mergedVariants.awaitingChoice())
        // Невыбранный вариант остаётся в черновике.
        assertEquals(2, chosen.mergedVariants.size)
    }

    @Test
    fun `у машины один черновик и ничего не изменилось — писать нечего`() {
        val docs = listOf(testDoc("a1", groupId = "A"))
        val dA = draft("dA", "a1", "A", materials = listOf(row("a1", "i", "Песок", "1")))

        val p = plan("A", listOf("a1"), listOf(dA), docs, preferredId = "dA")

        assertTrue(p.isNoop)
        assertEquals(dA, p.result)
    }

    // ── Разъединение ────────────────────────────────────────────────────────

    @Test
    fun `разъединили — строки ушедшего документа с правками уезжают в новый черновик его машины`() {
        // Был черновик объединённой машины A (a1 + b1). Менеджер разъединил:
        // b1 снова в своей машине B. Фото остаются в исходном черновике.
        val docs = listOf(testDoc("a1", groupId = "A"), testDoc("b1", groupId = "B", groupRevision = 3))
        val merged = draft(
            "dA", "a1", "A", loaded = listOf("a1", "b1"),
            materials = listOf(row("a1", "ia", "Арматура", "11"), row("b1", "ib", "Труба", "7")),
            cargo = listOf("/a.jpg"),
        )

        val p = plan("A", listOf("a1"), listOf(merged), docs, preferredId = "dA")

        val r = p.result!!
        assertEquals(listOf("Арматура"), r.materials.map { it.name })
        assertEquals(listOf("a1"), r.loadedDocIds)
        assertEquals(listOf("/a.jpg"), r.cargoPhotoPaths)
        val created = p.save.single { it.localDraftId == "new-1" }
        assertEquals("b1", created.updId)
        assertEquals("B", created.groupId)
        assertEquals(3, created.loadedGroupRevision)
        assertEquals(listOf("7"), created.materials.map { it.qty })
        assertTrue(created.cargoPhotoPaths.isEmpty())
    }

    @Test
    fun `открыли отделённую машину первой — её строки забираются из черновика соседа`() {
        val docs = listOf(testDoc("a1", groupId = "A"), testDoc("b1", groupId = "B"))
        val merged = draft(
            "dA", "a1", "A", loaded = listOf("a1", "b1"),
            materials = listOf(row("a1", "ia", "Арматура", "11"), row("b1", "ib", "Труба", "7")),
            cargo = listOf("/a.jpg"),
        )

        val p = plan("B", listOf("b1"), listOf(merged), docs)

        val r = p.result!!
        assertEquals("new-1", r.localDraftId)
        assertEquals(listOf("Труба"), r.materials.map { it.name })
        val donor = p.save.single { it.localDraftId == "dA" }
        assertEquals(listOf("Арматура"), donor.materials.map { it.name })
        assertEquals(listOf("/a.jpg"), donor.cargoPhotoPaths)
        assertTrue(p.delete.isEmpty())
    }

    @Test
    fun `сосед, отдавший все строки и не имевший фото, удаляется`() {
        // Сосед — черновик машины A (его якорь a1 в базе), но строки у него
        // только документа b1, ушедшего в машину B.
        val docs = listOf(testDoc("a1", groupId = "A"), testDoc("b1", groupId = "B"))
        val onlyB = draft("dX", "a1", "A", loaded = listOf("a1", "b1"), materials = listOf(row("b1", "ib", "Труба", "7")))

        val p = plan("B", listOf("b1"), listOf(onlyB), docs)

        assertEquals(listOf("dX"), p.delete)
        assertEquals(listOf("Труба"), p.result!!.materials.map { it.name })
    }

    @Test
    fun `якоря черновика нет в базе — черновик принадлежит машине загруженного документа`() {
        val docs = listOf(testDoc("b1", groupId = "B"))
        val dX = draft("dX", "a1", "A", loaded = listOf("a1", "b1"), materials = listOf(row("b1", "ib", "Труба", "7")))

        val p = plan("B", listOf("b1"), listOf(dX), docs)

        assertEquals("dX", p.result!!.localDraftId)
        assertTrue(p.delete.isEmpty())
        assertNotNull(p.result)
    }

    // ── Скрытый документ ────────────────────────────────────────────────────

    @Test
    fun `документа нет в базе — строки откладываются, вернулся — возвращаются`() {
        val dA = draft(
            "dA", "a1", "A", loaded = listOf("a1", "a2"),
            materials = listOf(row("a1", "i1", "Арматура", "11"), row("a2", "i2", "Цемент", "3")),
            cargo = listOf("/a.jpg"),
        )
        // a2 скрыт на переразборе: в базе его нет.
        val hidden = plan("A", listOf("a1"), listOf(dA), listOf(testDoc("a1", groupId = "A")), "dA").result!!
        assertEquals(listOf("Арматура"), hidden.materials.map { it.name })
        assertEquals(listOf("Цемент"), hidden.parkedMaterials.map { it.name })
        assertEquals(listOf("a1"), hidden.loadedDocIds)

        // a2 вернулся в ту же машину.
        val back = plan(
            "A", listOf("a1", "a2"), listOf(hidden),
            listOf(testDoc("a1", groupId = "A"), testDoc("a2", groupId = "A")), "dA",
        ).result!!
        assertEquals(listOf("Арматура", "Цемент"), back.materials.map { it.name })
        assertEquals(listOf("3"), back.materials.filter { it.sourceDocumentId == "a2" }.map { it.qty })
        assertTrue(back.parkedMaterials.isEmpty())
        // Документ снова «добавлен» — форма перечитает его позиции и сообщит.
        assertEquals(listOf("a1"), back.loadedDocIds)
    }

    // ── Правило «в черновике есть работа» ───────────────────────────────────

    @Test
    fun `черновик без фото с правкой количества хранится, без правок — нет`() {
        val pristine = listOf(row("a1", "i1", "Арматура", "11"))
        fun work(materials: List<MaterialDraft> = pristine, plate: String = "", parked: List<MaterialDraft> = emptyList()) =
            hasDraftWork(
                materials = materials, pristine = pristine, parked = parked,
                commentText = "", licensePlate = plate, manualUpdText = "", vehicleTypeCode = null,
                inTransit = false, isAssets = false, mergedVariants = emptyList(),
            )

        assertFalse(work())
        assertTrue(work(materials = listOf(row("a1", "i1", "Арматура", "10"))))
        assertTrue(work(materials = pristine + row(null, null, "Поддон", "1")))
        assertTrue(work(plate = "А111АА"))
        assertTrue(work(parked = listOf(row("a2", "i2", "Цемент", "3"))))
    }

    @Test
    fun `пустой черновик пустой, а с одной строкой — нет`() {
        assertTrue(draft("d", "a1", "A").isStructurallyEmpty())
        assertFalse(draft("d", "a1", "A", parked = listOf(row("x", "y", "Z", "1"))).isStructurallyEmpty())
        assertNull(plan("A", listOf("a1"), emptyList(), listOf(testDoc("a1", groupId = "A"))).result)
    }
}
