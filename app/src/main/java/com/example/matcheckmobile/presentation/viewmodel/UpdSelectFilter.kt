package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity

/**
 * Отбор УПД для экранов «Выбор УПД» (Въезд/Выезд) и счётчиков главного
 * Stages-экрана. Причина существования отдельной функции: /sync-эндпоинт
 * серверa уже фильтрует УПД по `siteId` инспектора, но stale-запись может
 * остаться в локальной Room (например при смене аккаунта без явного Logout —
 * iat-инвалидация токена, process death, ручная перепривязка админом).
 * Клиенту тогда нужна defensive проверка на UI-слое.
 *
 * Строгий фильтр `d.siteId == currentSiteId`: сервер УПД с `siteId=null`
 * инспектору никогда не отдаёт (SQL `eq(..., userSiteId)` не матчит NULL) —
 * значит документ без привязки к объекту в списке инспектора появляться не
 * должен. «Приёмка без УПД» в UI — это отдельная draft-строка с
 * `row.document = null`, она собирается из draft repository и через этот
 * фильтр не проходит.
 */
internal fun filterUpdDocsForSite(
    docs: List<RemoteSourceDocumentEntity>,
    currentSiteId: String,
    direction: String,
    attachedIds: Set<String>,
): List<RemoteSourceDocumentEntity> = docs.filter { d ->
    d.direction == direction &&
        d.id !in attachedIds &&
        d.siteId == currentSiteId
}
