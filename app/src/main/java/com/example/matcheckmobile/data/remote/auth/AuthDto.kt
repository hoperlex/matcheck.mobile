package com.example.matcheckmobile.data.remote.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val user: UserDto,
    val refreshToken: String? = null,
    val refreshExpiresIn: Long? = null,
)

@Serializable
data class RefreshResponse(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String? = null,
    val refreshExpiresIn: Long? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val role: String,
    val isActive: Boolean,
    val siteId: String? = null,
    val createdAt: String,
)

@Serializable
data class ApiErrorBody(
    val error: String? = null,
    @SerialName("message") val message: String? = null,
)
