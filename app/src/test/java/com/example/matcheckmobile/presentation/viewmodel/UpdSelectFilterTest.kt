package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.domain.model.testDoc
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

    @Test
    fun `привязка одного документа машины скрывает всю машину`() {
        // Иначе после оформления приёмки остаток документов той же загрузки
        // вернулся бы в список, и инспектор оформил бы тот же рейс второй раз —
        // ровно та проблема, ради которой машины и склеиваются.
        val docs = listOf(
            doc("upd-1", siteId = "site-1", direction = "inbound", groupId = "bundle-1"),
            doc("upd-2", siteId = "site-1", direction = "inbound", groupId = "bundle-1"),
            doc("wb-3", siteId = "site-1", direction = "inbound", groupId = "bundle-1"),
        )

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "site-1",
            direction = "inbound",
            attachedIds = setOf("upd-1"),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `чужая машина остаётся в списке`() {
        val docs = listOf(
            doc("upd-1", siteId = "site-1", direction = "inbound", groupId = "bundle-1"),
            doc("upd-2", siteId = "site-1", direction = "inbound", groupId = "bundle-2"),
        )

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "site-1",
            direction = "inbound",
            attachedIds = setOf("upd-1"),
        )

        assertEquals(listOf("upd-2"), result.map { it.id })
    }

    @Test
    fun `документы без машины фильтруются поштучно, как раньше`() {
        // groupId = null — legacy-сборка и документы из EDO/mail. Скрывать
        // «соседей» по null нельзя: у них нет ничего общего.
        val docs = listOf(
            doc("a", siteId = "site-1", direction = "inbound"),
            doc("b", siteId = "site-1", direction = "inbound"),
        )

        val result = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = "site-1",
            direction = "inbound",
            attachedIds = setOf("a"),
        )

        assertEquals(listOf("b"), result.map { it.id })
    }

    // --- Fixture helper ---

    private fun doc(
        id: String,
        siteId: String?,
        direction: String,
        groupId: String? = null,
    ): RemoteSourceDocumentEntity = testDoc(
        id = id,
        siteId = siteId,
        direction = direction,
        groupId = groupId,
    )
}
