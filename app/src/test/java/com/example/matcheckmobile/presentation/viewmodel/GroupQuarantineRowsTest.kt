package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import com.example.matcheckmobile.domain.model.testDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupQuarantineRowsTest {

    private fun row(
        reason: String,
        claimedAt: String? = null,
        reissuedAt: Long? = null,
        docs: String = """["u-1","u-2"]""",
        status: String = "filled",
    ) = QuarantinedOperationEntity(
        id = "q-1", entityType = "delivery", entityId = "d-1", reason = reason, claimedAt = claimedAt,
        siteId = "s-1", groupId = "g-1", sourceDocumentIdsJson = docs, vehiclePlate = "А123ВС77",
        statusCode = status, photoCount = 6, snapshotJson = "{}", reissuedAt = reissuedAt,
        // 20.11.2026 09:04 UTC = 12:04 МСК
        createdAt = 1_795_165_440_000L,
    )

    @Test
    fun `занятая машина — время занятия по Москве, оформить заново нельзя`() {
        val r = groupQuarantineRow(row("group_claimed", claimedAt = "2026-11-20T06:15:00.000Z"), emptyMap())

        assertEquals("Эту машину уже оформил другой планшет (09:15)", r.reason)
        assertFalse(r.canReissue)
    }

    @Test
    fun `изменился состав — можно оформить заново, пока черновик не создан`() {
        assertTrue(groupQuarantineRow(row("group_changed"), emptyMap()).canReissue)
        assertTrue(groupQuarantineRow(row("group_incomplete"), emptyMap()).canReissue)
        val done = groupQuarantineRow(row("group_changed", reissuedAt = 5L), emptyMap())
        assertFalse(done.canReissue)
        assertTrue(done.reissued)
    }

    @Test
    fun `машина подписана номерами документов, а без них — числом`() {
        val docs = mapOf(
            "u-1" to testDoc("u-1", docNumber = "УТ-134"),
            "u-2" to testDoc("u-2", docNumber = "85/11", kind = "transport_waybill"),
        )
        assertEquals("УПД УТ-134 · Накладная 85/11", groupQuarantineRow(row("group_changed"), docs).machine)
        assertEquals("Машина · 2 документа", groupQuarantineRow(row("group_changed"), emptyMap()).machine)
    }

    @Test
    fun `детали — госномер, фото, этапы и время по Москве`() {
        val r = groupQuarantineRow(row("group_changed", status = "confirmed_mol"), emptyMap())

        assertEquals("А123ВС77 · 6 фото · 1 и 2 Этап · 20.11 12:04", r.details)
    }
}
