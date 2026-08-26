package com.example.matcheckmobile.data.remote.net

import com.example.matcheckmobile.data.auth.SessionTokens
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.remote.auth.AuthApi
import com.example.matcheckmobile.data.remote.auth.LoginRequest
import com.example.matcheckmobile.data.remote.auth.LoginResponse
import com.example.matcheckmobile.data.remote.auth.RefreshResponse
import com.example.matcheckmobile.data.remote.auth.UserDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Единая точка обновления токена.
 *
 * Почему на это нужны тесты именно такого рода. Прежде обновлять токен умели три места, и
 * они не знали друг о друге: у `TokenAuthenticator` был приватный мьютекс, `refreshNow`
 * работал без замка вовсе, SSE не умел обновлять и на 401 стучался мёртвым токеном раз в
 * минуту. Два независимых обновления ротируют ОДИН refresh-токен, сервер считает повтор
 * атакой и гасит сессию — то есть инспектора выкидывает на экран входа посреди смены.
 * Поэтому здесь проверяется не «функция вернула значение», а отсутствие гонок и того,
 * что сессию гасят по ошибке.
 */
class TokenRefreshCoordinatorTest {

    // --- дублёры -----------------------------------------------------------

    private class FakeTokens(
        var access: String? = "A",
        var accessValid: Boolean = false,
        var refresh: String? = "R1",
        var user: String? = "u1",
    ) : SessionTokens {
        var cleared = false
        var savedAccess: String? = null
        /** Сколько раз запись/очистка были отвергнуты барьером сессии. */
        var rejected = 0

        override val accessToken: String? get() = access
        override fun isAccessTokenValid(marginMillis: Long): Boolean = accessValid
        override fun sessionKey(): TokenStorage.SessionKey = TokenStorage.SessionKey(refresh, user)

        override fun saveRefreshedTokensIfSessionMatches(
            expected: TokenStorage.SessionKey,
            accessToken: String,
            accessExpiresInSec: Long,
            refreshToken: String?,
            refreshExpiresInSec: Long?,
        ): Boolean {
            if (sessionKey() != expected) { rejected++; return false }
            access = accessToken
            accessValid = true
            savedAccess = accessToken
            if (refreshToken != null) refresh = refreshToken
            return true
        }

        override fun clearIfSessionMatches(expected: TokenStorage.SessionKey): Boolean {
            if (sessionKey() != expected) { rejected++; return false }
            cleared = true
            access = null
            refresh = null
            return true
        }
    }

    private open class FakeAuthApi : AuthApi {
        val calls = AtomicInteger(0)
        override suspend fun login(body: LoginRequest): LoginResponse = error("не используется")
        override suspend fun logout() = error("не используется")
        override suspend fun me(): UserDto = error("не используется")
        override suspend fun refresh(bearerRefresh: String): RefreshResponse {
            calls.incrementAndGet()
            return onRefresh(bearerRefresh)
        }
        open suspend fun onRefresh(bearerRefresh: String): RefreshResponse =
            RefreshResponse(accessToken = "B", expiresIn = 900, refreshToken = "R2", refreshExpiresIn = 3600)
    }

    private fun http401(): HttpException = HttpException(
        Response.error<Any>(401, "".toResponseBody("application/json".toMediaType())),
    )

    private fun coordinator(
        tokens: SessionTokens,
        api: AuthApi,
        onInvalidated: (String, Int) -> Unit = { _, _ -> },
    ) = TokenRefreshCoordinator(tokens, { api }, onInvalidated)

    // --- сериализация обновления ------------------------------------------

    @Test
    fun `два одновременных вызывающих дают один запрос refresh`() = runTest {
        val tokens = FakeTokens()
        val gate = CompletableDeferred<Unit>()
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse {
                gate.await() // держим первого внутри запроса, второй встаёт в очередь
                return RefreshResponse("B", 900, "R2", 3600)
            }
        }
        val c = coordinator(tokens, api)

        val first = async { c.obtainFresh("A", "/a") }
        val second = async { c.obtainFresh("A", "/b") }
        gate.complete(Unit)

        assertEquals(TokenRefreshCoordinator.Outcome.Fresh("B"), first.await())
        assertEquals(TokenRefreshCoordinator.Outcome.Fresh("B"), second.await())
        // Второй обязан ВЗЯТЬ ГОТОВОЕ, а не ротировать refresh-токен ещё раз:
        // именно повторная ротация и приводит к reuse-detection на сервере.
        assertEquals(1, api.calls.get())
    }

    @Test
    fun `Splash sync и SSE одновременно дают один запрос refresh`() = runTest {
        val tokens = FakeTokens()
        val gate = CompletableDeferred<Unit>()
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse {
                gate.await()
                return RefreshResponse("B", 900, "R2", 3600)
            }
        }
        val c = coordinator(tokens, api)

        // Splash зовёт без staleToken, sync и SSE — со своим протухшим.
        val splash = async { c.obtainFresh(null, "/auth/refresh") }
        val sync = async { c.obtainFresh("A", "/api/v1/sync") }
        val sse = async { c.obtainFresh("A", "/api/v1/events") }
        gate.complete(Unit)

        listOf(splash.await(), sync.await(), sse.await()).forEach {
            assertEquals(TokenRefreshCoordinator.Outcome.Fresh("B"), it)
        }
        assertEquals(1, api.calls.get())
    }

    @Test
    fun `вызывающий со свежим токеном обновление не инициирует`() = runTest {
        val tokens = FakeTokens(access = "A", accessValid = true)
        val api = FakeAuthApi()
        val outcome = coordinator(tokens, api).obtainFresh(staleToken = null, path = "/a")
        assertEquals(TokenRefreshCoordinator.Outcome.Fresh("A"), outcome)
        assertEquals(0, api.calls.get())
    }

    // --- барьер смены сессии ----------------------------------------------

    @Test
    fun `вход под другой учёткой во время refresh не затирает новую сессию`() = runTest {
        val tokens = FakeTokens(refresh = "R1", user = "u1")
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse {
                // Ровно в это окно пользователь вошёл под другим аккаунтом.
                tokens.refresh = "R-NEW"
                tokens.user = "u2"
                tokens.access = "NEW"
                return RefreshResponse("B", 900, "R2", 3600)
            }
        }
        val outcome = coordinator(tokens, api).obtainFresh("A", "/a")

        assertEquals(TokenRefreshCoordinator.Outcome.SessionChanged, outcome)
        assertNull("токены прежней сессии не должны лечь поверх новой", tokens.savedAccess)
        assertEquals("NEW", tokens.access)
        assertEquals(1, tokens.rejected)
    }

    @Test
    fun `отказ по старому refresh не гасит уже начатую новую сессию`() = runTest {
        val tokens = FakeTokens(refresh = "R1", user = "u1")
        var invalidations = 0
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse {
                tokens.refresh = "R-NEW"
                tokens.user = "u2"
                throw http401()
            }
        }
        val outcome = coordinator(tokens, api) { _, _ -> invalidations++ }.obtainFresh("A", "/a")

        assertEquals(TokenRefreshCoordinator.Outcome.SessionChanged, outcome)
        assertFalse("новая сессия обязана уцелеть", tokens.cleared)
        assertEquals("события разлогина быть не должно", 0, invalidations)
    }

    // --- исходы -----------------------------------------------------------

    @Test
    fun `без refresh-токена в сеть не ходим и разлогин не публикуем`() = runTest {
        val tokens = FakeTokens(refresh = null)
        val api = FakeAuthApi()
        var invalidations = 0
        val outcome = coordinator(tokens, api) { _, _ -> invalidations++ }.obtainFresh(null, "/a")

        assertEquals(TokenRefreshCoordinator.Outcome.NoSession, outcome)
        assertEquals(0, api.calls.get())
        // NoSession — это НЕ разлогин: гасить нечего. Иначе обычный выход
        // пользователя попал бы в журнал как протухший refresh.
        assertEquals(0, invalidations)
        assertFalse(tokens.cleared)
    }

    @Test
    fun `сетевая ошибка сессию не трогает`() = runTest {
        val tokens = FakeTokens()
        var invalidations = 0
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse = throw IOException("нет сети")
        }
        val outcome = coordinator(tokens, api) { _, _ -> invalidations++ }.obtainFresh("A", "/a")

        assertEquals(TokenRefreshCoordinator.Outcome.NetworkError, outcome)
        assertFalse("потеря связи не повод выкидывать инспектора", tokens.cleared)
        assertEquals(0, invalidations)
        assertEquals("R1", tokens.refresh)
    }

    @Test
    fun `отказ сервера гасит сессию ровно один раз`() = runTest {
        val tokens = FakeTokens()
        var invalidations = 0
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse = throw http401()
        }
        val c = coordinator(tokens, api) { _, _ -> invalidations++ }

        assertEquals(TokenRefreshCoordinator.Outcome.Invalid, c.obtainFresh("A", "/a"))
        assertTrue(tokens.cleared)
        assertEquals(1, invalidations)

        // Повторное обращение уже без сессии: второго события быть не должно —
        // иначе UI получит пачку «вас разлогинило» на один отказ.
        assertEquals(TokenRefreshCoordinator.Outcome.NoSession, c.obtainFresh("A", "/b"))
        assertEquals(1, invalidations)
    }

    @Test
    fun `ошибка не-401 считается сетевой и сессию не гасит`() = runTest {
        val tokens = FakeTokens()
        val api = object : FakeAuthApi() {
            override suspend fun onRefresh(bearerRefresh: String): RefreshResponse =
                throw HttpException(Response.error<Any>(500, "".toResponseBody("application/json".toMediaType())))
        }
        val outcome = coordinator(tokens, api).obtainFresh("A", "/a")
        assertEquals(TokenRefreshCoordinator.Outcome.NetworkError, outcome)
        assertFalse(tokens.cleared)
    }

    @Test
    fun `обновлённый токен возвращается вызывающему и сохраняется`() = runTest {
        val tokens = FakeTokens()
        val outcome = coordinator(tokens, FakeAuthApi()).obtainFresh("A", "/a")
        assertEquals(TokenRefreshCoordinator.Outcome.Fresh("B"), outcome)
        assertEquals("B", tokens.savedAccess)
        assertEquals("ротация refresh-токена обязана сохраниться", "R2", tokens.refresh)
    }
}
