package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сборка позиций приёмки из нескольких документов одной машины.
 *
 * Главное, что здесь ловится: сквозная нумерация lineNo. У второй УПД строки
 * снова начинаются с единицы, и если взять исходный номер строки документа,
 * финансы при финализации (`lineNo = idx + 1`) уедут к чужим позициям — цены и
 * НДС в карточке приёмки окажутся не от тех материалов.
 */
class GroupMaterialsBuilderTest {

    @Test
    fun `lineNo сквозной по всей машине, а не по каждому документу`() {
        val items = listOf(
            "upd-1" to item(id = "i1", lineNo = 1, price = "100"),
            "upd-1" to item(id = "i2", lineNo = 2, price = "200"),
            // Вторая УПД: её собственная нумерация снова с 1.
            "upd-2" to item(id = "i3", lineNo = 1, price = "300"),
            "upd-2" to item(id = "i4", lineNo = 2, price = "400"),
        )

        val built = buildGroupMaterials(items)

        assertEquals(listOf(1, 2, 3, 4), built.financials.map { it.lineNo })
        assertEquals(listOf("100", "200", "300", "400"), built.financials.map { it.price })
    }

    @Test
    fun `каждая позиция помнит свой документ и свою строку`() {
        val items = listOf(
            "upd-1" to item(id = "i1", lineNo = 1),
            "upd-2" to item(id = "i2", lineNo = 1),
        )

        val built = buildGroupMaterials(items)

        assertEquals(listOf("upd-1", "upd-2"), built.materials.map { it.sourceDocumentId })
        assertEquals(listOf("i1", "i2"), built.materials.map { it.sourceDocumentItemId })
    }

    @Test
    fun `датчик загрузки суммирует объём и массу по всем документам машины`() {
        // volumeM3 и massKg хранятся ПО ЕДИНИЦЕ материала, поэтому qty * value.
        val items = listOf(
            "upd-1" to item(id = "i1", lineNo = 1, qty = "2", volumeM3 = "1.5", massKg = "1000"),
            "upd-2" to item(id = "i2", lineNo = 1, qty = "3", volumeM3 = "1.0", massKg = "500"),
        )

        val built = buildGroupMaterials(items)

        assertEquals(6.0, built.loadInfo!!.totalVolumeM3!!, 1e-9)
        assertEquals(3.5, built.loadInfo!!.totalMassT!!, 1e-9)
    }

    @Test
    fun `нет объёма и массы ни у одной позиции — датчик пустой`() {
        val items = listOf("upd-1" to item(id = "i1", lineNo = 1))

        assertNull(buildGroupMaterials(items).loadInfo)
    }

    private fun item(
        id: String,
        lineNo: Int,
        qty: String = "1",
        price: String? = null,
        volumeM3: String? = null,
        massKg: String? = null,
    ) = RemoteSourceDocumentItemEntity(
        id = id,
        sourceDocumentId = "ignored",
        materialId = null,
        nameRaw = "Материал $id",
        qty = qty,
        unit = "шт",
        price = price,
        sum = null,
        vatRate = null,
        vatSum = null,
        expectedDate = null,
        lineNo = lineNo,
        volumeM3 = volumeM3,
        massKg = massKg,
        volumeConfidence = null,
        groupName = null,
    )

    // ── mergeGroupMaterials ─────────────────────────────────────────────────

    @Test
    fun `переразбор документа не оставляет форму со старыми количествами`() {
        // Сервер при повторном распознавании УДАЛЯЕТ позиции и создаёт заново с
        // новыми id (worker: DELETE ... WHERE source_document_id). Набор
        // документов при этом прежний, поэтому сверка по составу расхождения не
        // видит — и раньше форма молча финализировалась по старым количествам.
        val inForm = listOf(
            MaterialDraft(
                name = "Материал i1",
                qty = "5",
                unit = "шт",
                sourceDocumentId = "upd-1",
                sourceDocumentItemId = "i1",
            ),
        )
        // После переразбора: та же строка (то же название и единица), но другой
        // id и другое количество в документе.
        val reparsed = listOf(
            "upd-1" to listOf(
                item(id = "i1-new", lineNo = 1, qty = "9").copy(nameRaw = "Материал i1"),
            ),
        )

        val merged = mergeGroupMaterials(inForm, reparsed)

        assertEquals(1, merged.materials.size)
        // Привязка перешла на живую строку — мёртвого id не осталось.
        assertEquals("i1-new", merged.materials[0].sourceDocumentItemId)
        // Правка инспектора (5 вместо 1) уцелела: он её ввёл осознанно.
        assertEquals("5", merged.materials[0].qty)
    }

    @Test
    fun `позиции добавленного документа дописываются, а правки инспектора остаются`() {
        val inForm = listOf(
            MaterialDraft(
                name = "Материал i1",
                qty = "7",
                unit = "шт",
                sourceDocumentId = "upd-1",
                sourceDocumentItemId = "i1",
            ),
            // Своя строка инспектора — происхождения нет.
            MaterialDraft(name = "Поддон", qty = "2", unit = "шт"),
        )
        val docs = listOf(
            "upd-1" to listOf(item(id = "i1", lineNo = 1)),
            "upd-2" to listOf(item(id = "i2", lineNo = 1)),
        )

        val merged = mergeGroupMaterials(inForm, docs)

        assertEquals(3, merged.materials.size)
        assertEquals("7", merged.materials[0].qty)
        // Строка инспектора не тронута и не потеряла место.
        assertEquals("Поддон", merged.materials[1].name)
        assertNull(merged.materials[1].sourceDocumentId)
        // Позиция новой УПД дописана в конец.
        assertEquals("upd-2", merged.materials[2].sourceDocumentId)
        // Финансы пересобраны по НОВОЙ сквозной нумерации: строка инспектора
        // своих финансов не имеет, поэтому в списке только строки документов.
        assertEquals(listOf(1, 3), merged.financials.map { it.lineNo })
    }

    @Test
    fun `строки исчезнувшего документа убираются`() {
        val inForm = listOf(
            MaterialDraft(
                name = "Материал i1", qty = "1", unit = "шт",
                sourceDocumentId = "upd-1", sourceDocumentItemId = "i1",
            ),
            MaterialDraft(
                name = "Материал i2", qty = "1", unit = "шт",
                sourceDocumentId = "upd-2", sourceDocumentItemId = "i2",
            ),
        )
        // upd-2 из машины ушёл.
        val docs = listOf("upd-1" to listOf(item(id = "i1", lineNo = 1)))

        val merged = mergeGroupMaterials(inForm, docs)

        assertEquals(1, merged.materials.size)
        assertEquals("upd-1", merged.materials[0].sourceDocumentId)
    }

    @Test
    fun `две одинаковые строки документа не схлопываются в одну`() {
        // Сопоставление по названию расходует позицию однократно — иначе обе
        // строки формы прицепились бы к первой попавшейся, а вторая позиция
        // документа уехала бы в «добавленные» и задвоила материал.
        val inForm = listOf(
            MaterialDraft(
                name = "Материал i1", qty = "3", unit = "шт",
                sourceDocumentId = "upd-1", sourceDocumentItemId = "stale-a",
            ),
            MaterialDraft(
                name = "Материал i1", qty = "4", unit = "шт",
                sourceDocumentId = "upd-1", sourceDocumentItemId = "stale-b",
            ),
        )
        val docs = listOf(
            "upd-1" to listOf(
                item(id = "i1", lineNo = 1).copy(nameRaw = "Материал i1"),
                item(id = "i1b", lineNo = 2).copy(nameRaw = "Материал i1"),
            ),
        )

        val merged = mergeGroupMaterials(inForm, docs)

        assertEquals(2, merged.materials.size)
        assertEquals(listOf("i1", "i1b"), merged.materials.map { it.sourceDocumentItemId })
        assertEquals(listOf("3", "4"), merged.materials.map { it.qty })
    }
}
