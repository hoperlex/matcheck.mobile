package com.example.matcheckmobile.data.remote.api

import com.example.matcheckmobile.data.remote.api.dto.PhotoConfirmResponse
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignRequest
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignResponse
import com.example.matcheckmobile.data.remote.api.dto.PhotoUrlResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PhotosApi {

    @POST("api/v1/photos/presign")
    suspend fun presign(@Body body: PhotoPresignRequest): PhotoPresignResponse

    @POST("api/v1/photos/{id}/confirm")
    suspend fun confirm(@Path("id") id: String): PhotoConfirmResponse

    @GET("api/v1/photos/{id}/url")
    suspend fun url(
        @Path("id") id: String,
        @Query("thumb") thumb: Boolean? = null,
    ): PhotoUrlResponse

    @DELETE("api/v1/photos/{id}")
    suspend fun delete(@Path("id") id: String)
}
