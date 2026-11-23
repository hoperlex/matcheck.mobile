package com.example.matcheckmobile.data.remote.net

/**
 * Что умеет эта сборка — заголовок `X-Matcheck-Capabilities` на КАЖДОМ запросе
 * к API matcheck (включая /auth/refresh, SSE и повтор старых мутаций из очереди).
 *
 * Зачем на каждом, а не только в /sync. Сервер решает, можно ли пускать клиента
 * на объект с защитой «одна машина — одна приёмка», по той сборке, которая
 * ОТПРАВЛЯЕТ запрос и будет разбирать ответ. Мутация, поставленная в очередь
 * старой сборкой, после обновления уходит уже с этим заголовком — и сервер
 * проверяет её по новым правилам (typed-конфликт вместо молчаливой второй
 * приёмки), а новая сборка умеет такой ответ разобрать.
 *
 * - `source_groups_v1` — машина из нескольких документов приезжает в /sync
 *   одной группой (то же значение, что SyncRepository.SOURCE_GROUPS_CAPABILITY
 *   в query /sync);
 * - `group_claim_v1` — клиент понимает typed-конфликты группового протокола
 *   (`group_claimed`, `group_changed`, `group_incomplete`,
 *   `source_documents_not_ready`) и сам не создаст вторую приёмку машины.
 *
 * MUST MATCH SERVER: CAPABILITIES_HEADER и GROUP_CLAIM_CAPABILITY в
 * apps/api/src/domain/groups/claim-scope.ts.
 */
object ClientCapabilities {
    const val HEADER = "X-Matcheck-Capabilities"
    const val SOURCE_GROUPS = "source_groups_v1"
    const val GROUP_CLAIM = "group_claim_v1"

    /** Значение заголовка и поля `capabilities` в теле upsert: список через запятую. */
    const val ALL = "$SOURCE_GROUPS,$GROUP_CLAIM"
}
