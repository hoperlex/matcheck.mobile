package com.example.matcheckmobile.data.remote.api

import com.example.matcheckmobile.data.remote.api.dto.DeliveryDto
import com.example.matcheckmobile.data.remote.api.dto.DeliveryListResponse
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface DeliveriesApi {

    @GET("api/v1/deliveries")
    suspend fun list(
        @Query("trash") trash: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): DeliveryListResponse

    @GET("api/v1/deliveries/{id}")
    suspend fun get(@Path("id") id: String): DeliveryDto

    @POST("api/v1/deliveries")
    suspend fun upsert(@Body body: DeliveryUpsertRequest): DeliveryDto

    @DELETE("api/v1/deliveries/{id}")
    suspend fun delete(@Path("id") id: String)

    @POST("api/v1/deliveries/{id}/mark-deletion")
    suspend fun markDeletion(
        @Path("id") id: String,
        @Body body: MarkDeletionRequest,
    ): DeliveryDto

    @POST("api/v1/deliveries/{id}/unmark-deletion")
    suspend fun unmarkDeletion(@Path("id") id: String): DeliveryDto
}
