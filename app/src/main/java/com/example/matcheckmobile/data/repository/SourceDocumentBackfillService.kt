package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.local.mapper.RemoteMappers.toEntity
import com.example.matcheckmobile.data.remote.api.SourceDocumentsApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Дозагрузка УПД, привязанных к приёмкам/выездам, индивидуальными GET'ами.
 *
 * Контекст: серверный /sync для роли `inspector_kpp` фильтрует УПД, исключая
 * привязанные к приёмке или выезду (см. apps/api/src/routes/sync.ts). Поэтому
 * для приёмки в статусе `filled`/`confirmed_mol` мы не получаем её УПД через
 * дельту и не можем показать docNumber. Здесь — добор УПД по одной через
 * `GET /api/v1/source-documents/:id`.
 *
 * Дедупликация: уже-запрошенные id держим в [inFlight] до завершения запроса,
 * чтобы параллельные вызовы (Stage2 list + Stage2 form) не дублировали трафик.
 */
class SourceDocumentBackfillService(
    private val api: SourceDocumentsApi,
    private val dao: RemoteSourceDocumentDao,
) {

    private val inFlight = mutableSetOf<String>()
    private val mutex = Mutex()

    /**
     * Для каждого id из [ids], которого нет в локальной БД и который сейчас
     * не запрашивается, тянет полный DTO с сервера и сохраняет через
     * `saveAggregate`. Ошибки сети по конкретной УПД глушит — следующая
     * попытка случится при следующем вызове.
     */
    suspend fun ensureCached(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val toFetch = mutex.withLock {
            val missing = ids.toSet()
                .filter { id -> id !in inFlight && dao.findById(id) == null }
            inFlight.addAll(missing)
            missing
        }
        for (id in toFetch) {
            runCatching {
                val dto = api.get(id)
                dao.saveAggregate(
                    doc = dto.toEntity(),
                    items = dto.items.map { it.toEntity(dto.id) },
                    attachments = dto.attachments.map { it.toEntity(dto.id) },
                )
            }
            mutex.withLock { inFlight.remove(id) }
        }
    }
}
