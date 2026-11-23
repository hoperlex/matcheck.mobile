package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.remote.api.dto.SyncDeltaResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `deletedReasons` в /sync: строки черновиков убираются только у документов,
 * удалённых ОКОНЧАТЕЛЬНО. Скрытые и ответ старого сервера — ждут.
 */
class SyncDeletedReasonsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `удалённые окончательно отделяются от скрытых`() {
        val r = json.decodeFromString(
            SyncDeltaResponse.serializer(),
            """{"deletedIds":{"sourceDocuments":["a","b","c"]},""" +
                """"deletedReasons":{"sourceDocuments":{"a":"deleted","b":"hidden"}},""" +
                """"serverNow":"2026-11-20T00:00:00Z","cursor":"x"}""",
        )

        assertEquals(listOf("a"), deletedForGood(r))
    }

    @Test
    fun `ответ без причин (старый сервер) — ничего не удаляется окончательно`() {
        val r = json.decodeFromString(
            SyncDeltaResponse.serializer(),
            """{"deletedIds":{"sourceDocuments":["a"]},"serverNow":"2026-11-20T00:00:00Z","cursor":"x"}""",
        )

        assertNull(r.deletedReasons)
        assertEquals(emptyList<String>(), deletedForGood(r))
    }
}
