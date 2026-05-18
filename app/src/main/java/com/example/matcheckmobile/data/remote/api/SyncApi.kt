package com.example.matcheckmobile.data.remote.api

import com.example.matcheckmobile.data.remote.api.dto.SyncDeltaResponse
import retrofit2.http.GET
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
}
