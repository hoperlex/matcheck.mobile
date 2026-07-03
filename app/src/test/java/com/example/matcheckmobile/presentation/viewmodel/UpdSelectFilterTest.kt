package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdSelectFilterTest {

    @Test
    fun `stale-документ чужого siteId не попадает`() {
        val docs = listOf(
            doc(id = "1", siteId = "TEST", direction = "inbound"),
            doc(id = "2", siteId = "INJOY", direction = "inbound"),
        )

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "INJOY",
            direction = "inbound",
            attachedIds = emptySet(),
        )

        assertEquals(1, result.size)
        assertEquals("2", result.first().id)
    }

    @Test
    fun `документ текущего siteId попадает`() {
        val docs = listOf(doc(id = "1", siteId = "INJOY", direction = "inbound"))

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "INJOY",
            direction = "inbound",
            attachedIds = emptySet(),
        )

        assertEquals(1, result.size)
        assertEquals("1", result.first().id)
    }

    @Test
    fun `документ с siteId=null отфильтровывается`() {
        val docs = listOf(doc(id = "1", siteId = null, direction = "inbound"))

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "INJOY",
            direction = "inbound",
            attachedIds = emptySet(),
        )

        assertTrue("null-siteId документ должен быть отфильтрован", result.isEmpty())
    }

    @Test
    fun `фильтр по direction работает — inbound не показывается в outbound-запросе`() {
        val docs = listOf(
            doc(id = "1", siteId = "INJOY", direction = "inbound"),
            doc(id = "2", siteId = "INJOY", direction = "outbound"),
        )

        val inbound = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "INJOY",
            direction = "inbound",
            attachedIds = emptySet(),
        )
        val outbound = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "INJOY",
            direction = "outbound",
            attachedIds = emptySet(),
        )

        assertEquals(listOf("1"), inbound.map { it.id })
        assertEquals(listOf("2"), outbound.map { it.id })
    }

    @Test
    fun `attachedIds исключает уже-привязанные`() {
        val docs = listOf(
            doc(id = "1", siteId = "INJOY", direction = "inbound"),
            doc(id = "2", siteId = "INJOY", direction = "inbound"),
        )

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "INJOY",
            direction = "inbound",
            attachedIds = setOf("1"),
        )

        assertEquals(listOf("2"), result.map { it.id })
    }

    @Test
    fun `пустой docs даёт пустой результат`() {
        val result = filterUpdDocsForSite(
            docs = emptyList(),
            currentSiteId = "INJOY",
            direction = "inbound",
            attachedIds = emptySet(),
        )

        assertTrue(result.isEmpty())
    }

    // --- Fixture helper ---

    private fun doc(
        id: String,
        siteId: String?,
        direction: String,
    ): RemoteSourceDocumentEntity = RemoteSourceDocumentEntity(
        id = id,
        kind = "upd",
        direction = direction,
        status = "unaccepted",
        supplierId = null,
        recipientId = null,
        contractorId = null,
        recipientMolId = null,
        siteId = siteId,
        supplierName = null,
        contractorName = null,
        siteName = null,
        createdByUserPhone = null,
        docNumber = null,
        docDate = null,
        totalSum = null,
        vatSum = null,
        expectedDate = null,
        origin = "web",
        parsedAt = "2026-07-02T00:00:00Z",
        parseErrorCode = null,
        originalFilename = null,
        version = 1,
        createdAt = "2026-07-02T00:00:00Z",
        updatedAt = "2026-07-02T00:00:00Z",
    )
}
