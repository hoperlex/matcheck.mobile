package com.example.matcheckmobile.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Семантика ошибки push-мутации к /deliveries|/shipments.
 *
 * Зеркалит контракт [packages/contracts/openapi.json]:
 * - OCC 409 → ConflictResponse {error: "conflict", serverVersion, server}
 * - soft-delete 409/400 → ErrorResponse {error: <код>, message?, details?}
 */
sealed interface MutationFailure {

    /** OCC-конфликт версий. В теле — полный server-snapshot. Требует UI разрешения. */
    data class Conflict(val serverVersion: Int) : MutationFailure

    /**
     * Документ помечен на удаление. Любые мутации запрещены до unmark-deletion.
     * Локальная копия должна быть освежена с сервера, чтобы UI ушёл в read-only.
     */
    data object PendingDeletion : MutationFailure

    /** mark-deletion по уже помеченному. Локальная копия отстала. */
    data object AlreadyPending : MutationFailure

    /** unmark-deletion по не помеченному. Локальная копия отстала. */
    data object NotPending : MutationFailure

    /**
     * DELETE filled/confirmed_mol/shipped без предварительного mark.
     * Inspector_kpp такое не должен выполнять — финальный DELETE только у admin.
     */
    data object MustMarkFirst : MutationFailure

    /**
     * mark-deletion по draft/not_filled. Клиент должен был вызвать обычный DELETE.
     */
    data object CannotMarkStatus : MutationFailure

    /** Нераспознанная ошибка: 5xx → backoff, 4xx → drop. */
    data class Other(val httpCode: Int, val errorCode: String?) : MutationFailure
}

/**
 * Чистая функция: на основании HTTP-кода и тела ошибки возвращает семантику.
 *
 * Алгоритм: декодируем тело как generic JsonObject, читаем поле `error` и
 * (опционально) `serverVersion`. Это устойчиво к расширению контракта
 * (новые поля в ErrorResponse не валят парсинг).
 */
fun classifyMutationFailure(httpCode: Int, rawBody: String?): MutationFailure {
    if (rawBody.isNullOrBlank()) {
        return MutationFailure.Other(httpCode, errorCode = null)
    }

    val parsed = runCatching { JSON.parseToJsonElement(rawBody).jsonObject }.getOrNull()
        ?: return MutationFailure.Other(httpCode, errorCode = null)

    val errorCode = parsed["error"]?.jsonPrimitive?.contentOrNull
    val serverVersion = parsed["serverVersion"]?.jsonPrimitive
        ?.let { it.intOrNull ?: it.longOrNull?.toInt() }

    return when (errorCode) {
        "conflict" -> serverVersion
            ?.let { MutationFailure.Conflict(it) }
            ?: MutationFailure.Other(httpCode, errorCode)
        "pending_deletion" -> MutationFailure.PendingDeletion
        "already_pending" -> MutationFailure.AlreadyPending
        "not_pending" -> MutationFailure.NotPending
        "must_mark_first" -> MutationFailure.MustMarkFirst
        "cannot_mark_status" -> MutationFailure.CannotMarkStatus
        else -> MutationFailure.Other(httpCode, errorCode)
    }
}

/** Короткий тег для записи в `lastError` мутации. */
val MutationFailure.tag: String
    get() = when (this) {
        is MutationFailure.Conflict -> "conflict(v=$serverVersion)"
        MutationFailure.PendingDeletion -> "pending_deletion"
        MutationFailure.AlreadyPending -> "already_pending"
        MutationFailure.NotPending -> "not_pending"
        MutationFailure.MustMarkFirst -> "must_mark_first"
        MutationFailure.CannotMarkStatus -> "cannot_mark_status"
        is MutationFailure.Other -> "http $httpCode${errorCode?.let { " ($it)" } ?: ""}"
    }

private val JSON = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
