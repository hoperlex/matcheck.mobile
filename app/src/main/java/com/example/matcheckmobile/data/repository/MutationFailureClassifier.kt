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

    /**
     * 403 `foreign_site`: запись принадлежит ДРУГОМУ объекту, сервер отказал в
     * изменении. Такое бывает только с остатками прошлого аккаунта в локальной
     * базе — их нужно удалить целиком (запись + все её мутации + файлы фото),
     * а не держать в очереди как конфликт. См. MutationProcessor.Outcome.PurgeForeign.
     */
    data object ForeignSite : MutationFailure

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
        "foreign_site" -> MutationFailure.ForeignSite
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
        MutationFailure.ForeignSite -> "foreign_site"
        is MutationFailure.Other -> "http $httpCode${errorCode?.let { " ($it)" } ?: ""}"
    }

private val JSON = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Что делать с мутацией, чью ошибку не удалось разобрать по семантике. */
enum class HttpDisposition {
    /** Повторить позже: сервер до содержимого запроса не добрался. */
    RETRY,

    /** Не повторять: состояние клиента разошлось с сервером, retry бесполезен. */
    DROP,
}

/**
 * Судьба нераспознанной HTTP-ошибки. Чистая функция — вынесена из
 * `MutationProcessor.classifyFailure`, чтобы правило проверялось юнит-тестом,
 * а не через Room и Retrofit.
 *
 * 401 здесь вместе с 5xx намеренно. Протухший access-токен — ошибка транспорта,
 * а не семантики: сервер payload даже не читал (глобальный onRequest-хук режет
 * запрос до роутов). Обычно его гасит [TokenAuthenticator], обновляя токен и
 * повторяя запрос, но у того есть ветки, где 401 уходит наверх — в частности
 * сетевой сбой самого refresh'а. Раньше такой 401 приводил к `Drop`, и операция
 * инспектора терялась навсегда: завершённый 2 Этап откатывался к `filled` на
 * ближайшем pull, а мутация замораживалась и больше не повторялась.
 *
 * 408/429 сюда сознательно НЕ добавлены: к разбираемому инциденту отношения не
 * имеют, а область изменения расширяют.
 */
fun httpDisposition(httpCode: Int): HttpDisposition =
    if (httpCode == 401 || httpCode in 500..599) HttpDisposition.RETRY else HttpDisposition.DROP
