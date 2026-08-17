package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбивка материалов машины по документам.
 *
 * Главное, что здесь стережётся, — неизменность исходного списка. Финализация
 * нумерует позиции как `idx + 1` ПОСЛЕ отбрасывания пустых строк и по этому же
 * номеру подмешивает цены и НДС. Стоит разбивке переставить или отфильтровать
 * что-то в state.materials — деньги уедут к чужим позициям, и никакой ошибки
 * при этом не возникнет. Поэтому проверяется не только результат, но и то, что
 * вход остался прежним.
 */
class MaterialDisplayGroupsTest {

    private fun draft(name: String, docId: String?, qty: String = "1") =
        MaterialDraft(name = name, qty = qty, unit = "шт", sourceDocumentId = docId)

    private val docs = listOf(
        GroupDocumentLabel(id = "upd-1", number = "КА-1518"),
        GroupDocumentLabel(id = "upd-2", number = "УТ-2217"),
    )

    @Test
    fun `две УПД дают два блока с номерами и своими позициями`() {
        val materials = listOf(
            draft("Цемент", "upd-1"),
            draft("Песок", "upd-1"),
            draft("Арматура", "upd-2"),
        )

        val groups = buildMaterialDisplayGroups(materials, docs)

        assertEquals(2, groups.size)
        assertEquals("Материалы КА-1518", groups[0].label)
        assertEquals(listOf("Цемент", "Песок"), groups[0].materials.map { it.name })
        assertEquals("Материалы УТ-2217", groups[1].label)
        assertEquals(listOf("Арматура"), groups[1].materials.map { it.name })
    }

    @Test
    fun `порядок блоков идёт за порядком документов машины, а не за порядком позиций`() {
        // Позиции второй УПД стоят первыми, но блоки обязаны идти в порядке
        // sortGroupDocs — иначе форма и карточка на портале разойдутся.
        val materials = listOf(
            draft("Арматура", "upd-2"),
            draft("Цемент", "upd-1"),
        )

        val groups = buildMaterialDisplayGroups(materials, docs)

        assertEquals(listOf("Материалы КА-1518", "Материалы УТ-2217"), groups.map { it.label })
    }

    @Test
    fun `исходный список не меняется — ни составом, ни порядком`() {
        val materials = listOf(
            draft("Цемент", "upd-1"),
            draft("Арматура", "upd-2"),
            draft("Своя строка", null),
        )
        val before = materials.toList()

        buildMaterialDisplayGroups(materials, docs)

        assertEquals(before, materials)
        // Элементы те же самые объекты: копирование здесь не нужно и означало бы
        // потерю ссылок sourceDocumentItemId при следующей правке.
        before.forEachIndexed { idx, item -> assertSame(item, materials[idx]) }
    }

    @Test
    fun `строки инспектора идут отдельным блоком без номера, в конце`() {
        val materials = listOf(
            draft("Цемент", "upd-1"),
            draft("Внесено вручную", null),
        )

        val groups = buildMaterialDisplayGroups(materials, docs)

        assertEquals(2, groups.size)
        assertEquals("Материалы КА-1518", groups[0].label)
        assertEquals("Материалы", groups[1].label)
        assertNull(groups[1].documentId)
        assertEquals(listOf("Внесено вручную"), groups[1].materials.map { it.name })
    }

    @Test
    fun `позиции документа, ушедшего из машины, не теряются`() {
        // Инспектор их видел и мог править: молча выбросить нельзя.
        val materials = listOf(
            draft("Цемент", "upd-1"),
            draft("Из удалённой УПД", "upd-99"),
        )

        val groups = buildMaterialDisplayGroups(materials, docs)

        val all = groups.flatMap { it.materials }
        assertTrue(all.any { it.name == "Из удалённой УПД" })
        assertEquals(materials.size, all.size)
    }

    @Test
    fun `документ без позиций блока не даёт`() {
        val materials = listOf(draft("Цемент", "upd-1"))

        val groups = buildMaterialDisplayGroups(materials, docs)

        assertEquals(1, groups.size)
        assertEquals("Материалы КА-1518", groups[0].label)
    }

    @Test
    fun `документ без номера подписывается словами, а не null`() {
        val materials = listOf(
            draft("Цемент", "upd-1"),
            draft("Арматура", "upd-3"),
        )
        val withUnnamed = docs + GroupDocumentLabel(id = "upd-3", number = null)

        val groups = buildMaterialDisplayGroups(materials, withUnnamed)

        assertEquals("Материалы без номера", groups.last().label)
    }

    @Test
    fun `одиночная УПД остаётся одним блоком без номера в заголовке`() {
        // Номер и так стоит в шапке формы — дублировать его незачем.
        val materials = listOf(draft("Цемент", "upd-1"), draft("Песок", "upd-1"))

        val groups = buildMaterialDisplayGroups(materials, listOf(docs[0]))

        assertEquals(1, groups.size)
        assertEquals("Материалы", groups[0].label)
        assertEquals(2, groups[0].materials.size)
    }

    @Test
    fun `пустая форма блоков не даёт`() {
        assertEquals(emptyList<MaterialDisplayGroup>(), buildMaterialDisplayGroups(emptyList(), docs))
        assertEquals(emptyList<MaterialDisplayGroup>(), buildMaterialDisplayGroups(emptyList(), emptyList()))
    }

    @Test
    fun `после разбивки финализация отдаёт каждой позиции деньги её документа`() {
        // Самая дорогая ошибка этой задачи — не «блок не тот», а «цена не та».
        // Неизменности списка для доказательства мало: финализация нумерует
        // позиции ПОСЛЕ отбрасывания пустых строк (`lineNo = idx + 1`) и по
        // этому номеру достаёт цену из materialFinancials. Поэтому тест
        // повторяет весь путь: сборка машины → разбивка для экрана → та самая
        // нумерация.
        val items = listOf(
            "upd-1" to itemOf("i1", price = "100", vat = "20"),
            "upd-1" to itemOf("i2", price = "200", vat = "40"),
            "upd-2" to itemOf("i3", price = "300", vat = "60"),
        )
        val built = buildGroupMaterials(items)

        buildMaterialDisplayGroups(built.materials, docs)

        // Ровно то, что делает finalizeStage1.
        val byLine = built.financials.associateBy { it.lineNo }
        val finalized = built.materials
            .filter { it.name.isNotBlank() || it.qty.isNotBlank() }
            .mapIndexed { idx, m -> Triple(m.sourceDocumentId, byLine[idx + 1]?.price, byLine[idx + 1]?.vatSum) }

        assertEquals(
            listOf(
                Triple("upd-1", "100", "20"),
                Triple("upd-1", "200", "40"),
                Triple("upd-2", "300", "60"),
            ),
            finalized,
        )
    }

    private fun itemOf(id: String, price: String, vat: String) = RemoteSourceDocumentItemEntity(
        id = id,
        sourceDocumentId = "ignored",
        materialId = null,
        nameRaw = "Позиция $id",
        qty = "1",
        unit = "шт",
        price = price,
        sum = null,
        vatRate = "20",
        vatSum = vat,
        expectedDate = null,
        lineNo = 1,
        volumeM3 = null,
        massKg = null,
        volumeConfidence = null,
        groupName = null,
    )

    @Test
    fun `сумма позиций по блокам равна исходному списку — ничего не теряется и не двоится`() {
        val materials = listOf(
            draft("Цемент", "upd-1"),
            draft("Песок", "upd-1"),
            draft("Арматура", "upd-2"),
            draft("Своя строка", null),
        )

        val groups = buildMaterialDisplayGroups(materials, docs)

        assertEquals(materials.size, groups.sumOf { it.materials.size })
        assertEquals(materials.toSet(), groups.flatMap { it.materials }.toSet())
    }
}
