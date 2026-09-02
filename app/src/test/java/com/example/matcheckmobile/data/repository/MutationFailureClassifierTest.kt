package com.example.matcheckmobile.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationFailureClassifierTest {

    @Test
    fun `OCC conflict с serverVersion даёт Conflict(version)`() {
        val body = """{"error":"conflict","serverVersion":7,"server":{"id":"x"}}"""

        val result = classifyMutationFailure(httpCode = 409, rawBody = body)

        assertEquals(MutationFailure.Conflict(serverVersion = 7), result)
        assertEquals("conflict(v=7)", result.tag)
    }

    @Test
    fun `conflict без serverVersion трактуется как Other`() {
        val body = """{"error":"conflict"}"""

        val result = classifyMutationFailure(httpCode = 409, rawBody = body)

        assertEquals(MutationFailure.Other(httpCode = 409, errorCode = "conflict"), result)
    }

    @Test
    fun `foreign_site распознаётся отдельно от прочих 403`() {
        val body = """{"error":"foreign_site","message":"Запись другого объекта"}"""

        val result = classifyMutationFailure(httpCode = 403, rawBody = body)

        assertEquals(MutationFailure.ForeignSite, result)
        assertEquals("foreign_site", result.tag)
    }

    @Test
    fun `прочий 403 остаётся Other — purge только по явному коду`() {
        val result = classifyMutationFailure(403, """{"error":"forbidden"}""")

        assertEquals(MutationFailure.Other(httpCode = 403, errorCode = "forbidden"), result)
    }

    @Test
    fun `pending_deletion код распознаётся`() {
        val body = """{"error":"pending_deletion","message":"document is marked"}"""

        assertEquals(MutationFailure.PendingDeletion, classifyMutationFailure(409, body))
    }

    @Test
    fun `already_pending код распознаётся`() {
        assertEquals(
            MutationFailure.AlreadyPending,
            classifyMutationFailure(409, """{"error":"already_pending"}"""),
        )
    }

    @Test
    fun `not_pending код распознаётся`() {
        assertEquals(
            MutationFailure.NotPending,
            classifyMutationFailure(409, """{"error":"not_pending"}"""),
        )
    }

    @Test
    fun `must_mark_first код распознаётся`() {
        assertEquals(
            MutationFailure.MustMarkFirst,
            classifyMutationFailure(409, """{"error":"must_mark_first"}"""),
        )
    }

    @Test
    fun `cannot_mark_status приходит с 400 — корректно распознаётся`() {
        val result = classifyMutationFailure(400, """{"error":"cannot_mark_status"}""")

        assertEquals(MutationFailure.CannotMarkStatus, result)
        assertEquals("cannot_mark_status", result.tag)
    }

    @Test
    fun `пустое тело трактуется как Other(code=null)`() {
        val result = classifyMutationFailure(500, rawBody = "")

        assertEquals(MutationFailure.Other(httpCode = 500, errorCode = null), result)
        assertEquals("http 500", result.tag)
    }

    @Test
    fun `мусор вместо JSON трактуется как Other`() {
        val result = classifyMutationFailure(409, rawBody = "<html>nginx error</html>")

        assertEquals(MutationFailure.Other(httpCode = 409, errorCode = null), result)
    }

    @Test
    fun `unknown error string возвращает Other с errorCode`() {
        val result = classifyMutationFailure(403, """{"error":"forbidden","message":"x"}""")

        assertEquals(MutationFailure.Other(httpCode = 403, errorCode = "forbidden"), result)
        assertEquals("http 403 (forbidden)", result.tag)
    }

    @Test
    fun `serverVersion как long корректно приводится к Int`() {
        val body = """{"error":"conflict","serverVersion":42,"server":{}}"""

        val result = classifyMutationFailure(409, body)

        assertTrue(result is MutationFailure.Conflict)
        assertEquals(42, (result as MutationFailure.Conflict).serverVersion)
    }

    @Test
    fun `extra fields в body игнорируются`() {
        val body = """{"error":"pending_deletion","extra":"x","nested":{"y":1}}"""

        assertEquals(MutationFailure.PendingDeletion, classifyMutationFailure(409, body))
    }

    // --- httpDisposition: судьба нераспознанной ошибки ---
    //
    // Регрессия инцидента 28.08.2026: 401 трактовался как Drop, и завершённый
    // инспектором 2 Этап терялся навсегда (приёмка 12652).

    @Test
    fun `реальный глобальный 401 доходит до httpDisposition как Other`() {
        // Тело именно такое: global_auth_required — строка причины в
        // unauthorized_access_log, а не поле ответа (apps/api plugins/auth.ts).
        val result = classifyMutationFailure(401, """{"error":"unauthorized"}""")

        assertEquals(MutationFailure.Other(httpCode = 401, errorCode = "unauthorized"), result)
        assertEquals("http 401 (unauthorized)", result.tag)
    }

    @Test
    fun `401 повторяется, а не выбрасывается`() {
        assertEquals(HttpDisposition.RETRY, httpDisposition(401))
    }

    @Test
    fun `5xx повторяется как и раньше`() {
        assertEquals(HttpDisposition.RETRY, httpDisposition(500))
        assertEquals(HttpDisposition.RETRY, httpDisposition(502))
        assertEquals(HttpDisposition.RETRY, httpDisposition(599))
    }

    @Test
    fun `прочие 4xx по-прежнему выбрасываются`() {
        assertEquals(HttpDisposition.DROP, httpDisposition(400))
        assertEquals(HttpDisposition.DROP, httpDisposition(403))
        assertEquals(HttpDisposition.DROP, httpDisposition(404))
        assertEquals(HttpDisposition.DROP, httpDisposition(409))
    }

    @Test
    fun `408 и 429 намеренно не входят в этот фикс`() {
        assertEquals(HttpDisposition.DROP, httpDisposition(408))
        assertEquals(HttpDisposition.DROP, httpDisposition(429))
    }
}
