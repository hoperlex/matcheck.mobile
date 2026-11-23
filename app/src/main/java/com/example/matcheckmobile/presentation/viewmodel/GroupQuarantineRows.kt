package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.repository.GroupQuarantine
import com.example.matcheckmobile.domain.BusinessTime
import com.example.matcheckmobile.domain.model.docCountLabel
import com.example.matcheckmobile.domain.model.sourceDocGroupTitle
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Строка «Очереди» для операции в карантине группового протокола.
 *
 * Вся подача — чистыми функциями: формулировки для инспектора и правило
 * «когда можно оформить заново» проверяются тестом без Compose.
 */
data class GroupQuarantineRow(
    val id: String,
    /** «Эту машину уже оформил другой планшет (09:15)» / «Состав машины изменился». */
    val reason: String,
    /** Что за машина: номера документов, иначе их число. */
    val machine: String,
    /** «А123ВС77 · 6 фото · 1 и 2 Этап · 20.11 12:04». */
    val details: String,
    /** Кнопка «Оформить заново»: машину не оформил другой планшет и черновик ещё не создавали. */
    val canReissue: Boolean,
    val reissued: Boolean,
)

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** Причина простыми словами. Время занятия — по Москве, как всё в приложении. */
fun groupQuarantineReason(reason: String, claimedAt: String?): String = when (reason) {
    "group_claimed" -> {
        val at = claimedAt
            ?.let { runCatching { Instant.parse(it).atZone(BusinessTime.ZONE).format(TIME) }.getOrNull() }
        "Эту машину уже оформил другой планшет" + (at?.let { " ($it)" } ?: "")
    }
    "source_documents_not_ready" -> "Документы машины ещё обрабатываются — оформите её заново позже"
    else -> "Состав машины изменился — оформите её заново"
}

fun groupQuarantineRow(
    row: QuarantinedOperationEntity,
    docsById: Map<String, RemoteSourceDocumentEntity>,
): GroupQuarantineRow {
    val ids = RemoteMappers.decodeIdList(row.sourceDocumentIdsJson)
    val docs = ids.mapNotNull { docsById[it] }
    val machine = when {
        docs.isNotEmpty() -> sourceDocGroupTitle(docs)
        ids.isNotEmpty() -> "Машина · ${docCountLabel(ids.size)}"
        else -> "Приёмка без документов"
    }
    val stages = if (row.statusCode == "confirmed_mol") "1 и 2 Этап" else "1 Этап"
    val details = listOfNotNull(
        row.vehiclePlate?.takeIf(String::isNotBlank),
        "${row.photoCount} фото",
        stages,
        Instant.ofEpochMilli(row.createdAt).atZone(BusinessTime.ZONE).format(DATE_TIME),
    ).joinToString(" · ")
    return GroupQuarantineRow(
        id = row.id,
        reason = groupQuarantineReason(row.reason, row.claimedAt),
        machine = machine,
        details = details,
        canReissue = GroupQuarantine.canReissue(row.reason) && row.reissuedAt == null,
        reissued = row.reissuedAt != null,
    )
}
