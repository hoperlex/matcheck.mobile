package com.example.matcheckmobile.data.remote.api

import com.example.matcheckmobile.data.remote.api.dto.ReconcileRequestDto
import com.example.matcheckmobile.data.remote.api.dto.ReconcileResponseDto
import com.example.matcheckmobile.data.remote.api.dto.SyncDeltaResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface SyncApi {

    /**
     * Дельта-синхронизация. Без [since] — initial-sync, окно ограничивается [windowDays].
     * С [since] (ISO-8601) — все объекты с `updatedAt >= since`.
     */
    @GET("api/v1/sync")
    suspend fun delta(
        @Query("since") since: String? = null,
        @Query("windowDays") windowDays: Int? = null,
        /**
         * Что умеет клиент, список через запятую. Ровно этим сервер решает,
         * отдавать ли групповой контракт: без параметра он ВСЕГДА отвечает
         * по-старому, даже если объект уже переведён в новый режим
         * (см. серверный domain/groups/group-mode.ts).
         */
        @Query("capabilities") capabilities: String? = null,
        /**
         * Токен следующей страницы из `nextPageToken` предыдущего ответа.
         * Только для группового режима: он гарантирует, что машина не будет
         * разрезана границей страницы.
         */
        @Query("pageToken") pageToken: String? = null,
    ): SyncDeltaResponse

    /**
     * Read-only сверка планшет ↔ сервер. Клиент шлёт локальные id+version,
     * сервер отвечает расхождениями (missingOnClient/staleOnClient/
     * missingOnServer) в зоне видимости инспектора. Ничего не меняет на сервере.
     */
    @POST("api/v1/sync/reconcile")
    suspend fun reconcile(@Body body: ReconcileRequestDto): ReconcileResponseDto
}
