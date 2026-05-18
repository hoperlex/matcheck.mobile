package com.example.matcheckmobile.data.remote.auth

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /**
     * Refresh передаёт refresh-token в Authorization (mobile-flow).
     * Заголовок проставляется руками, чтобы [com.example.matcheckmobile.data.remote.net.AuthHeaderInterceptor]
     * не перетёр его access-токеном.
     */
    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Header("Authorization") bearerRefresh: String): RefreshResponse

    @POST("api/v1/auth/logout")
    suspend fun logout()

    @GET("api/v1/auth/me")
    suspend fun me(): UserDto
}
