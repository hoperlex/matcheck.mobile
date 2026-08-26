package com.example.matcheckmobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила отображения и слияния «машины» — документов одной загрузки с
 * веб-портала.
 *
 * Порядок здесь не косметика: по нему позиции получают сквозной lineNo, с
 * которым потом сходятся цены и НДС из УПД. Поэтому он проверяется отдельно.
 */
class SourceDocGroupTest {

    @Test
    fun `УПД идут перед накладными, внутри вида — по номеру`() {
        val docs = listOf(
            testDoc("c", kind = "transport_waybill", docNumber = "192"),
            testDoc("b", kind = "upd", docNumber = "1404"),
            testDoc("a", kind = "upd", docNumber = "1403"),
        )

        assertEquals(listOf("a", "b", "c"), sortGroupDocs(docs).map { it.id })
    }

    @Test
    fun `порядок стабилен при равных номерах — тай-брейк по id`() {
        val docs = listOf(
            testDoc("z", kind = "upd", docNumber = null),
            testDoc("y", kind = "upd", docNumber = null),
        )

        assertEquals(listOf("y", "z"), sortGroupDocs(docs).map { it.id })
        assertEquals(listOf("y", "z"), sortGroupDocs(docs.reversed()).map { it.id })
    }

    @Test
    fun `заголовок машины группирует номера по видам`() {
        val docs = listOf(
            testDoc("c", kind = "transport_waybill", docNumber = "192"),
            testDoc("a", kind = "upd", docNumber = "1403"),
            testDoc("b", kind = "upd", docNumber = "1404"),
        )

        assertEquals("УПД 1403, 1404 · Накладная 192", sourceDocGroupTitle(docs))
    }

    @Test
    fun `одиночный документ даёт прежний заголовок`() {
        val docs = listOf(testDoc("a", kind = "upd", docNumber = "1403"))

        assertEquals(sourceDocTitle("upd", "1403"), sourceDocGroupTitle(docs))
    }

    @Test
    fun `документ без номера показывается прочерком, а не пропадает`() {
        // Инспектор должен видеть, что в машине есть ещё одна бумага, даже если
        // распознать её номер не удалось.
        val docs = listOf(
            testDoc("a", kind = "upd", docNumber = "1403"),
            testDoc("b", kind = "upd", docNumber = null),
        )

        assertEquals("УПД 1403, —", sourceDocGroupTitle(docs))
    }

    @Test
    fun `плюрализация счётчика документов`() {
        assertEquals("1 документ", docCountLabel(1))
        assertEquals("2 документа", docCountLabel(2))
        assertEquals("5 документов", docCountLabel(5))
        assertEquals("11 документов", docCountLabel(11))
        assertEquals("14 документов", docCountLabel(14))
        assertEquals("21 документ", docCountLabel(21))
        assertEquals("22 документа", docCountLabel(22))
    }

    @Test
    fun `реквизиты собираются по первому непустому, а не с якоря`() {
        // Боевой случай (корень b5536153…): подрядчик есть только у УПД,
        // грузополучатель — только у транспортной накладной. Возьми мы всё с
        // первого документа, приёмка уехала бы с прочерком в грузополучателе.
        val docs = listOf(
            testDoc(
                "upd-1",
                kind = "upd",
                docNumber = "1389",
                contractorId = "contractor-1",
                supplierId = "supplier-1",
                consigneeName = null,
            ),
            testDoc(
                "wb-1",
                kind = "transport_waybill",
                docNumber = "187",
                contractorId = null,
                supplierId = "supplier-1",
                consigneeName = "ООО \"СУ-10\"",
            ),
        )

        val party = mergeGroupParty(docs)

        assertEquals("contractor-1", party.contractorId)
        assertEquals("ООО \"СУ-10\"", party.consigneeName)
        assertEquals("supplier-1", party.supplierId)
    }

    @Test
    fun `пустая строка не выигрывает у значения`() {
        val docs = listOf(
            testDoc("a", kind = "upd", consigneeName = "   "),
            testDoc("b", kind = "upd", consigneeName = "ООО Ромашка"),
        )

        assertEquals("ООО Ромашка", mergeGroupParty(docs).consigneeName)
    }

    @Test
    fun `конфликт непустых значений разрешается детерминированно — первый по порядку`() {
        val docs = listOf(
            testDoc("b", kind = "upd", docNumber = "2", contractorId = "second"),
            testDoc("a", kind = "upd", docNumber = "1", contractorId = "first"),
        )

        assertEquals("first", mergeGroupParty(docs).contractorId)
        assertEquals("first", mergeGroupParty(docs.reversed()).contractorId)
    }

    @Test
    fun `нет значения ни в одном документе — null`() {
        val docs = listOf(testDoc("a"), testDoc("b"))

        assertNull(mergeGroupParty(docs).contractorId)
    }

    @Test
    fun `три документа одной машины дают одну группу, три без машины — три`() {
        val machine = listOf(
            testDoc("a", groupId = "bundle-1", docNumber = "1403"),
            testDoc("b", groupId = "bundle-1", docNumber = "1404"),
            testDoc("c", groupId = "bundle-1", kind = "transport_waybill", docNumber = "192"),
        )
        assertEquals(1, groupDocsByMachine(machine).size)
        assertEquals(listOf("a", "b", "c"), groupDocsByMachine(machine).first().map { it.id })

        val loose = listOf(testDoc("x"), testDoc("y"), testDoc("z"))
        assertEquals(3, groupDocsByMachine(loose).size)
    }

    @Test
    fun `порядок групп стабилен между пересборками`() {
        val docs = listOf(
            testDoc("b", groupId = "bundle-2"),
            testDoc("a", groupId = "bundle-1"),
        )

        assertEquals(
            groupDocsByMachine(docs).map { it.first().id },
            groupDocsByMachine(docs.reversed()).map { it.first().id },
        )
    }

    @Test
    fun `черновик, начатый до группировки, находит свою машину по якорному документу`() {
        // Ключевой сценарий обновления приложения: у черновика заполнен только
        // updId, а карточка в списке ищет себя по id машины. Без этого моста
        // инспектор потерял бы бейдж «Начато» и уже снятые фото.
        val docsById = mapOf("upd-1" to testDoc("upd-1", groupId = "bundle-1"))

        val key = draftGroupKey(draftGroupId = null, draftUpdId = "upd-1", docsById = docsById)

        assertEquals("bundle-1", key)
    }

    @Test
    fun `у нового черновика ключ берётся из него самого`() {
        val key = draftGroupKey(
            draftGroupId = "bundle-1",
            draftUpdId = "upd-1",
            docsById = emptyMap(),
        )

        assertEquals("bundle-1", key)
    }

    @Test
    fun `документ без машины — ключ по самому документу`() {
        val docsById = mapOf("upd-1" to testDoc("upd-1", groupId = null))

        assertEquals(
            "upd-1",
            draftGroupKey(draftGroupId = null, draftUpdId = "upd-1", docsById = docsById),
        )
        // Документа ещё нет в локальном кэше — тоже падаем на updId, а не в null:
        // черновик не должен «осиротеть» из-за не доехавшей дельты.
        assertEquals(
            "upd-1",
            draftGroupKey(draftGroupId = null, draftUpdId = "upd-1", docsById = emptyMap()),
        )
    }

    @Test
    fun `пустая приёмка без документа ключа не имеет`() {
        assertNull(draftGroupKey(draftGroupId = null, draftUpdId = null, docsById = emptyMap()))
    }

    @Test
    fun `ключ группировки — машина, а для одиночного документа его собственный id`() {
        assertEquals("bundle-1", groupKeyOf(testDoc("a", groupId = "bundle-1")))
        assertEquals("a", groupKeyOf(testDoc("a", groupId = null)))
    }

    @Test
    fun `подпись строки — грузополучатель, иначе покупатель, подрядчика нет никогда`() {
        // Графа 4 распозналась — показываем её.
        assertEquals("Грузополучатель: ООО Ромашка", partyLabel("ООО Ромашка", "ООО Покупатель"))
        // Графы 4 нет — вторая ступень, И ПОДПИСЬ МЕНЯЕТСЯ ВМЕСТЕ СО ЗНАЧЕНИЕМ:
        // оставить «Грузополучатель» с содержимым графы 6 значило бы соврать
        // инспектору о том, что он читает.
        assertEquals("Покупатель: ООО Покупатель", partyLabel(null, "ООО Покупатель"))
        assertEquals("Покупатель: ООО Покупатель", partyLabel("   ", "ООО Покупатель"))
        // Нет ни того, ни другого — прочерк. Подрядчик сюда не подставляется:
        // на портале он скрыт из таблиц документов.
        assertEquals("Грузополучатель: —", partyLabel(null, null))
        assertEquals("Грузополучатель: —", partyLabel("", "  "))
    }

    @Test
    fun `смешанная машина не подписывает накладные как УПД`() {
        // Боевая приёмка 94060cff…: одна УПД и две транспортные накладные.
        // Прежний код брал ОДИН префикс по первому привязанному документу и
        // выдавал «УПД 14040/19, 19-19684-1 +1», то есть называл накладные УПД
        // и прятал третий документ. Комментарий в коде утверждал, что таких
        // приёмок не бывает; за 30 дней их 16.
        val docs = listOf(
            testDoc("wb-2", kind = "transport_waybill", docNumber = "01-06-2026/50"),
            testDoc("upd-1", kind = "upd", docNumber = "14040/19"),
            testDoc("wb-1", kind = "transport_waybill", docNumber = "19-19684-1"),
        )

        assertEquals(
            "УПД 14040/19 · Накладная 01-06-2026/50, 19-19684-1",
            attachedDocsGroupTitle(docs, manualUpd = null),
        )
    }

    @Test
    fun `перечисляются все номера, а не первые два`() {
        // Регрессия на UPD_SUMMARY_MAX_INLINE = 2: списки 2 Этапа и Архива
        // схлопывали хвост в «+N», и инспектор не мог найти поставку по номеру
        // третьего документа.
        val docs = (1..5).map { testDoc("upd-$it", kind = "upd", docNumber = "$it") }

        assertEquals("УПД 1, 2, 3, 4, 5", attachedDocsGroupTitle(docs, manualUpd = null))
    }

    @Test
    fun `документ без номера виден и в привязанной машине`() {
        val docs = listOf(
            testDoc("a", kind = "upd", docNumber = "1403"),
            testDoc("b", kind = "upd", docNumber = null),
        )

        assertEquals("УПД 1403, —", attachedDocsGroupTitle(docs, manualUpd = null))
    }

    @Test
    fun `ручная метка проигрывает реальным номерам документов`() {
        // Иначе приёмка с распознанной УПД подписалась бы текстом, который
        // инспектор набрал руками ещё до привязки документа.
        val docs = listOf(testDoc("a", kind = "upd", docNumber = "1403"))

        assertEquals("УПД 1403", attachedDocsGroupTitle(docs, manualUpd = "999"))
    }

    @Test
    fun `ручная метка идёт в ход, когда документов нет вовсе`() {
        assertEquals("УПД 999", attachedDocsGroupTitle(emptyList(), manualUpd = "999"))
        // Пустая строка меткой не считается — иначе заголовок стал бы «УПД ».
        assertNull(attachedDocsGroupTitle(emptyList(), manualUpd = "   "))
    }

    @Test
    fun `документы есть, номеров нет — показываем документы, а не метку из комментария`() {
        // Прежний код в этом месте падал на комментарий и терял тот факт, что
        // бумаг в машине две. Экран решает сам, как назвать пустоту.
        val docs = listOf(
            testDoc("a", kind = "transport_waybill", docNumber = null),
            testDoc("b", kind = "transport_waybill", docNumber = null),
        )

        assertEquals("Накладная —, —", attachedDocsGroupTitle(docs, manualUpd = null))
    }

    @Test
    fun `сказать нечего — null, а не пустая подпись`() {
        assertNull(attachedDocsGroupTitle(emptyList(), manualUpd = null))
    }
}
