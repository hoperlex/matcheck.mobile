package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поля группового протокола в теле upsert и разморозка наследия 426.
 */
class GroupProtocolFieldsTest {

    private fun req(statusCode: String = "filled") = DeliveryUpsertRequest(
        id = "d-1",
        statusCode = statusCode,
        siteId = "s-1",
        sourceDocumentIds = listOf("u-1", "u-2"),
    )

    @Test
    fun `форма 1 Этапа передаёт машину и версию — они и уходят`() {
        val out = inheritGroupFields(req(), "g-1", 7, baseVersion = 0, previous = null)

        assertEquals("g-1", out.groupId)
        assertEquals(7, out.groupRevision)
    }

    @Test
    fun `оба этапа офлайн — 2 Этап наследует машину из заменяемой мутации 1 Этапа`() {
        val stage1 = req().copy(groupId = "g-1", groupRevision = 7)

        val out = inheritGroupFields(req("confirmed_mol"), null, null, baseVersion = 0, previous = stage1)

        assertEquals("g-1", out.groupId)
        assertEquals(7, out.groupRevision)
    }

    @Test
    fun `приёмка уже на сервере — правка машину не наследует`() {
        val stage1 = req().copy(groupId = "g-1", groupRevision = 7)

        val out = inheritGroupFields(req("confirmed_mol"), null, null, baseVersion = 3, previous = stage1)

        assertNull(out.groupId)
        assertNull(out.groupRevision)
    }

    @Test
    fun `мутация прошлой сборки без новых полей декодируется`() {
        // Ровно то, что писала сборка до group_claim_v1 (encodeDefaults=true).
        val legacy = """{"id":"d-1","statusCode":"filled","siteId":"s-1","supplierId":null,""" +
            """"sourceDocumentIds":["u-1"],"items":[],"baseVersion":0}"""
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        val decoded = json.decodeFromString(DeliveryUpsertRequest.serializer(), legacy)

        assertNull(decoded.groupId)
        assertNull(decoded.groupRevision)
        assertNull(decoded.capabilities)
    }

    @Test
    fun `426 старой сборки размораживается, свой — нет`() {
        // Старая сборка писала «http 426 · » + тело ответа.
        assertTrue(isLegacy426Freeze("""http 426 · {"error":"client_outdated","message":"Обновите"}"""))
        assertTrue(isLegacy426Freeze("http 426 · null"))
        // Свой 426 — поломка заголовка: разморозка крутилась бы на каждом цикле.
        assertFalse(isLegacy426Freeze(OWN_426_MARKER))
        assertFalse(isLegacy426Freeze("http 409 · group_claimed"))
        assertFalse(isLegacy426Freeze(null))
    }
}
