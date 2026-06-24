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
    ): SyncDeltaResponse

    /**
     * Read-only сверка планшет ↔ сервер. Клиент шлёт локальные id+version,
     * сервер отвечает расхождениями (missingOnClient/staleOnClient/
     * missingOnServer) в зоне видимости инспектора. Ничего не меняет на сервере.
     */
    @POST("api/v1/sync/reconcile")
    suspend fun reconcile(@Body body: ReconcileRequestDto): ReconcileResponseDto
}
