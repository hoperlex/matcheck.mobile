package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.remote.auth.ApiErrorBody
import com.example.matcheckmobile.data.remote.auth.AuthApi
import com.example.matcheckmobile.data.remote.auth.LoginRequest
import com.example.matcheckmobile.data.remote.auth.UserDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * Точка входа в аутентификацию. Скрывает за собой Retrofit/Storage,
 * нормализует серверные ошибки до [LoginError], публикует события сессии
 * (logged-out при invalid_refresh).
 */
class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStorage: TokenStorage,
) {

    val session: StateFlow<TokenStorage.Snapshot> = tokenStorage.state

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    fun isAuthenticated(): Boolean = tokenStorage.isAuthenticated()

    suspend fun login(email: String, password: String): Result<UserDto> = runCatching {
        val response = authApi.login(LoginRequest(email = email.trim(), password = password))
        val refresh = response.refreshToken
        val refreshTtl = response.refreshExpiresIn
        if (refresh == null || refreshTtl == null) {
            // Сервер не вернул refresh в теле — значит X-Client-Type: mobile не дошёл.
            throw IllegalStateException("server didn't return refreshToken in body")
        }
        tokenStorage.saveSession(
            accessToken = response.accessToken,
            accessExpiresInSec = response.expiresIn,
            refreshToken = refresh,
            refreshExpiresInSec = refreshTtl,
            userId = response.user.id,
            userEmail = response.user.email,
            role = response.user.role,
            siteId = response.user.siteId,
        )
        response.user
    }.recoverCatching { throwable -> throw mapLoginError(throwable) }

    suspend fun me(): Result<UserDto> = runCatching { authApi.me() }

    suspend fun logout(): Result<Unit> = runCatching {
        try {
            authApi.logout()
        } catch (_: HttpException) {
            // Даже если сервер ругнётся — локальную сессию всё равно зачищаем.
        } catch (_: IOException) {
            // Сеть упала: всё равно logout локально.
        }
        tokenStorage.clear()
        _sessionEvents.tryEmit(SessionEvent.LoggedOut(reason = SessionEvent.LogoutReason.USER))
    }

    /** Вызывается из [com.example.matcheckmobile.data.remote.net.TokenAuthenticator]. */
    fun notifySessionInvalidated() {
        _sessionEvents.tryEmit(SessionEvent.LoggedOut(reason = SessionEvent.LogoutReason.REFRESH_INVALID))
    }

    private fun mapLoginError(error: Throwable): LoginException {
        if (error is HttpException) {
            val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val code = raw?.let { runCatching { json.decodeFromString(ApiErrorBody.serializer(), it) }.getOrNull() }
            return when (error.code()) {
                400 -> LoginException(LoginError.WeakPassword)
                401 -> when (code?.error) {
                    "account_inactive" -> LoginException(LoginError.AccountInactive)
                    else -> LoginException(LoginError.InvalidCredentials)
                }
                423 -> LoginException(LoginError.AccountLocked)
                429 -> LoginException(LoginError.TooManyAttempts)
                in 500..599 -> LoginException(LoginError.ServerError)
                else -> LoginException(LoginError.Unknown(error.message()))
            }
        }
        if (error is IOException) return LoginException(LoginError.Network)
        return LoginException(LoginError.Unknown(error.message))
    }

    sealed interface SessionEvent {
        enum class LogoutReason { USER, REFRESH_INVALID }
        data class LoggedOut(val reason: LogoutReason) : SessionEvent
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

sealed class LoginError {
    data object InvalidCredentials : LoginError()
    data object AccountInactive : LoginError()
    data object AccountLocked : LoginError()
    data object TooManyAttempts : LoginError()
    data object WeakPassword : LoginError()
    data object Network : LoginError()
    data object ServerError : LoginError()
    data class Unknown(val message: String?) : LoginError()
}

class LoginException(val error: LoginError) : RuntimeException(error.toString())
