package com.example.matcheckmobile.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentAttachmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Замена агрегата документа при повторном распознавании на портале.
 *
 * Ради чего тест. Документ, у которого сорвался разбор, уезжает на планшет
 * пустым — карточка с прочерками. После успешного повтора сервер присылает его
 * заново, и [RemoteSourceDocumentDao.saveAggregate] обязан заменить и шапку, и
 * позиции. Если бы он вставлял с IGNORE или только добавлял строки, инспектор
 * увидел бы прежние прочерки либо задвоенный список материалов — а заметно это
 * стало бы уже на объекте.
 *
 * Отдельно проверяется ПУСТОЙ новый список позиций: `saveAggregate` вызывает
 * `replaceItems` только при непустом наборе, и удаление обязано жить снаружи
 * этого условия. Ошибись он — документ, у которого повтор убрал позиции,
 * навсегда сохранил бы старые.
 *
 * Настоящая Room (in-memory), а не подделка: проверяется поведение транзакции
 * и каскадов, которое fake воспроизвести не может.
 */
@RunWith(AndroidJUnit4::class)
class SourceDocumentAggregateReplaceTest {

    private lateinit var db: MatcheckDatabase
    private val docId = UUID.randomUUID().toString()

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** Каким документ приезжает, пока разбор не удался: ни номера, ни сумм. */
    private fun brokenDoc() = RemoteSourceDocumentEntity(
        id = docId,
        kind = "upd",
        direction = "inbound",
        status = "needs_resolution",
        supplierId = null,
        recipientId = null,
        contractorId = null,
        recipientMolId = null,
        siteId = "site-1",
        supplierName = null,
        contractorName = null,
        consigneeName = null,
        buyerName = null,
        siteName = null,
        createdByUserPhone = null,
        docNumber = null,
        docDate = null,
        totalSum = null,
        vatSum = null,
        expectedDate = "2026-08-27",
        origin = "manual_pdf",
        parsedAt = "2026-08-27T09:00:00Z",
        parseErrorCode = "not_processed",
        originalFilename = "скан.pdf",
        version = 1,
        createdAt = "2026-08-27T09:00:00Z",
        updatedAt = "2026-08-27T09:00:00Z",
        groupId = null,
        groupRevision = null,
    )

    /** Он же после успешного повтора: реквизиты на месте, версия выросла. */
    private fun repairedDoc() = brokenDoc().copy(
        status = "parsed",
        parseErrorCode = null,
        docNumber = "УТ-6071",
        docDate = "2026-08-26",
        totalSum = "209229.80",
        supplierName = "ООО «Поставщик»",
        consigneeName = "ООО «Грузополучатель»",
        version = 2,
        updatedAt = "2026-08-27T09:32:12Z",
    )

    private fun item(no: Int, name: String) = RemoteSourceDocumentItemEntity(
        id = UUID.randomUUID().toString(),
        sourceDocumentId = docId,
        materialId = null,
        nameRaw = name,
        qty = "1",
        unit = "шт",
        price = null,
        sum = null,
        vatRate = null,
        vatSum = null,
        expectedDate = null,
        lineNo = no,
        volumeM3 = null,
        massKg = null,
        volumeConfidence = null,
        groupName = null,
    )

    private fun attachment(name: String) = RemoteSourceDocumentAttachmentEntity(
        id = UUID.randomUUID().toString(),
        sourceDocumentId = docId,
        s3Key = "s3/$name",
        filename = name,
        mimeType = "application/pdf",
        sizeBytes = 1024,
        role = "original",
    )

    @Test
    fun повторное_распознавание_заменяет_реквизиты_и_позиции() = runBlocking {
        val dao = db.remoteSourceDocumentDao()

        dao.saveAggregate(brokenDoc(), listOf(item(1, "мусорная строка")), listOf(attachment("скан.pdf")))
        assertEquals(1, dao.findItemsBySource(docId).size)

        dao.saveAggregate(
            doc = repairedDoc(),
            items = listOf(item(1, "Плита"), item(2, "Профиль")),
            attachments = listOf(attachment("скан.pdf")),
        )

        val saved = dao.findById(docId)!!
        assertEquals("УТ-6071", saved.docNumber)
        assertEquals("parsed", saved.status)
        assertEquals(null, saved.parseErrorCode)
        assertEquals(2, saved.version)

        // Старая строка обязана исчезнуть, а не остаться рядом с новыми.
        val items = dao.findItemsBySource(docId)
        assertEquals(2, items.size)
        assertEquals(listOf("Плита", "Профиль"), items.map { it.nameRaw })
        assertTrue(items.none { it.nameRaw == "мусорная строка" })
    }

    @Test
    fun пустой_новый_список_позиций_тоже_очищает_старые() = runBlocking {
        val dao = db.remoteSourceDocumentDao()

        dao.saveAggregate(brokenDoc(), listOf(item(1, "Плита"), item(2, "Профиль")), emptyList())
        assertEquals(2, dao.findItemsBySource(docId).size)

        // saveAggregate зовёт replaceItems только при непустом наборе, поэтому
        // удаление обязано стоять ДО проверки. Иначе документ, у которого
        // повтор снял позиции, сохранил бы старые навсегда.
        dao.saveAggregate(repairedDoc(), emptyList(), emptyList())

        assertEquals(0, dao.findItemsBySource(docId).size)
        assertEquals("УТ-6071", dao.findById(docId)!!.docNumber)
    }
}
