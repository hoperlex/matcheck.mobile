package com.example.matcheckmobile.data.remote.api

import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentListResponse
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ShipmentsApi {

    @GET("api/v1/shipments")
    suspend fun list(
        @Query("trash") trash: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): ShipmentListResponse

    @GET("api/v1/shipments/{id}")
    suspend fun get(@Path("id") id: String): ShipmentDto

    @POST("api/v1/shipments")
    suspend fun upsert(@Body body: ShipmentUpsertRequest): ShipmentDto

    @DELETE("api/v1/shipments/{id}")
    suspend fun delete(@Path("id") id: String)

    @POST("api/v1/shipments/{id}/mark-deletion")
    suspend fun markDeletion(
        @Path("id") id: String,
        @Body body: MarkDeletionRequest,
    ): ShipmentDto

    @POST("api/v1/shipments/{id}/unmark-deletion")
    suspend fun unmarkDeletion(@Path("id") id: String): ShipmentDto
}
