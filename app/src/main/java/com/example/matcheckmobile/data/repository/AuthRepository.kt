package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.auth.AccountSwitchBlocked
import com.example.matcheckmobile.data.auth.PendingWork
import com.example.matcheckmobile.data.auth.AccountWipeFailed
import com.example.matcheckmobile.data.auth.LogoutAuditLog
import com.example.matcheckmobile.data.auth.SessionGate
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.remote.auth.ApiErrorBody
import com.example.matcheckmobile.data.remote.auth.AuthApi
import com.example.matcheckmobile.data.remote.auth.LoginRequest
import com.example.matcheckmobile.data.remote.auth.UserDto
import com.example.matcheckmobile.data.remote.net.TokenRefreshCoordinator
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
    private val logoutAuditLog: LogoutAuditLog,
    /**
     * Общий координатор обновления токена. Через него же ходят
     * `TokenAuthenticator` и SSE — три точки обязаны делить один замок, иначе
     * ротируют refresh-токен наперегонки (см. [TokenRefreshCoordinator]).
     */
    private val coordinator: TokenRefreshCoordinator,
) {

    val session: StateFlow<TokenStorage.Snapshot> = tokenStorage.state

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    fun isAuthenticated(): Boolean = tokenStorage.isAuthenticated()

    /**
     * Вход. [onBeforeActivate] вызывается ПОСЛЕ успешного ответа сервера, но ДО
     * сохранения токенов — там координатор смены аккаунта чистит локальную базу
     * прошлого пользователя (см. AccountSwitchCoordinator). Если очистка упадёт,
     * сессия не активируется и старая остаётся в силе.
     *
     * Весь блок закрыт [SessionGate]: фоновый sync не должен работать ни во
     * время очистки, ни в окне между очисткой и сохранением новых токенов —
     * иначе он либо отправит чужие записи, либо снова наполнит базу данными
     * старого аккаунта.
     */
    suspend fun login(
        email: String,
        password: String,
        onBeforeActivate: suspend (UserDto) -> Unit = {},
    ): Result<UserDto> = runCatching {
        SessionGate.begin()
        try {
            val response = authApi.login(LoginRequest(email = email.trim(), password = password))
            val refresh = response.refreshToken
            val refreshTtl = response.refreshExpiresIn
            if (refresh == null || refreshTtl == null) {
                // Сервер не вернул refresh в теле — значит X-Client-Type: mobile не дошёл.
                throw IllegalStateException("server didn't return refreshToken in body")
            }
            onBeforeActivate(response.user)
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
        } finally {
            SessionGate.end()
        }
    }.recoverCatching { throwable -> throw mapLoginError(throwable) }

    suspend fun me(): Result<UserDto> = runCatching { authApi.me() }

    /**
     * Подтягивает с сервера актуального user.siteId и пишет в tokenStorage,
     * если он изменился. Нужно, когда админ поменял объект инспектора —
     * без перезапроса /me мобила хранила бы старый siteId до следующего
     * полного logout/login, и штамп на фото 1-го Этапа писал бы старый
     * объект (см. Stage1FormViewModel.resolveSiteName).
     *
     * Все ошибки (сеть, 401, 5xx, парсинг) тихо проглатываются: метод
     * вызывается как side-effect после успешного syncOnce и не должен
     * валить вызывающего. Если /me не удалось — siteId просто останется
     * прежним до следующей попытки.
     *
     * Defensive guard: НЕ затираем валидный локальный siteId, если /auth/me
     * вернул null. Был реальный инцидент (инспектор ЗИЛ33, 2026-06-18): на
     * одном из двух планшетов с одного и того же аккаунта siteId внезапно
     * стал null — finalizeStage1 падал с «Нет привязки к объекту», второй
     * планшет под тем же аккаунтом работал нормально. Это значит, что на
     * сервере siteId реально валидный, но /auth/me временно отдал null
     * (race с серверным кешем / кратковременная серверная ошибка), и мы
     * сами себе прострелили ногу, записав null поверх живого значения.
     * Если админ действительно снимет привязку, мобила всё равно споткнётся
     * на серверной проверке при ближайшем upsert (CHECK / 401 / 422) —
     * инспектор честно перезайдёт. Зато транзиентный кривой ответ /auth/me
     * больше не убивает рабочую сессию.
     *
     * Возвращает true, если значение действительно обновилось.
     */
    suspend fun refreshSiteIdFromServer(): Boolean {
        val fetched = fetchServerSiteId() ?: return false
        return tokenStorage.updateSiteId(fetched)
    }

    /**
     * Возвращает объект пользователя с сервера, **не трогая TokenStorage**, или
     * null, если менять нечего (сеть упала, /me вернул то же значение, либо
     * сработал guard против затирания живого siteId пустым).
     *
     * Разделение чтения и записи — не косметика. Раньше siteId сначала
     * сохранялся, и только потом писался персистентный долг на сброс snapshot;
     * смерть процесса между этими шагами делала смену объекта невидимой
     * навсегда (следующий /me возвращал уже сохранённое значение). Теперь
     * вызывающий сам решает порядок: сначала долг, потом токен.
     * См. AppContainer.refreshSiteIdAfterPull.
     */
    suspend fun fetchServerSiteId(): String? {
        val me = runCatching { authApi.me() }.getOrNull() ?: return null
        val current = tokenStorage.state.value.siteId
        // Пустой siteId с сервера поверх живого — не применяем (см. выше).
        if (me.siteId.isNullOrBlank() && !current.isNullOrBlank()) return null
        val fetched = me.siteId
        if (fetched.isNullOrBlank() || fetched == current) return null
        return fetched
    }

    /**
     * Принудительный refresh access-token до выполнения бизнес-запросов.
     * Используется на сплеше: если refresh ещё валиден — пользователь
     * не увидит flicker LOGIN-MAIN при истёкшем access. Если сервер
     * вернёт 401 — координатор стирает токены и фиксирует в audit log
     * (ровно один раз на погашенную сессию, см. onSessionInvalidated).
     *
     * @return true — если refresh успешен и сессия живая;
     *         false — если refresh-token нет в storage, либо сервер
     *         сказал invalid_refresh, либо сеть упала
     *         (в последнем случае сессия не считается мёртвой — она
     *         оживёт при первом удачном запросе).
     */
    suspend fun refreshNow(): RefreshOutcome {
        // Через общий координатор, а не своим запросом: раньше здесь не было
        // замка вовсе, и от гонки со стартующими в onCreate sync-воркером и SSE
        // спасала только проверка «access ещё жив». Спасала не всегда — сервер
        // ловил reuse одного refresh-токена двумя одновременными запросами и
        // отвечал 401, то есть ложным разлогином при обычном закрытии-открытии
        // приложения. Быстрый путь «access валиден» переехал внутрь координатора.
        return when (coordinator.obtainFresh(staleToken = null, path = REFRESH_PATH)) {
            is TokenRefreshCoordinator.Outcome.Fresh -> RefreshOutcome.Ok

            // Сессию сменили, пока шло обновление: вошли заново или под другой
            // учёткой. Для сплеша это «сессия есть» — работаем дальше, гасить
            // и уводить на логин нечего.
            TokenRefreshCoordinator.Outcome.SessionChanged -> RefreshOutcome.Ok

            // Обновляться нечем. НЕ разлогин: сессии нет, значит и события
            // REFRESH_INVALID быть не должно — иначе обычный выход попадёт в
            // журнал как протухший refresh.
            TokenRefreshCoordinator.Outcome.NoSession -> RefreshOutcome.NoRefresh

            TokenRefreshCoordinator.Outcome.Invalid -> RefreshOutcome.Invalid

            // Сеть недоступна — сессия не считается мёртвой, оживёт при первом
            // удачном запросе. Сплеш по этому исходу продолжает работу.
            TokenRefreshCoordinator.Outcome.NetworkError -> RefreshOutcome.NetworkError
        }
    }

    suspend fun logout(): Result<Unit> = runCatching {
        try {
            authApi.logout()
        } catch (_: HttpException) {
            // Даже если сервер ругнётся — локальную сессию всё равно зачищаем.
        } catch (_: IOException) {
            // Сеть упала: всё равно logout локально.
        }
        tokenStorage.clear()
        logoutAuditLog.record(reason = "USER_LOGOUT")
        _sessionEvents.tryEmit(SessionEvent.LoggedOut(reason = SessionEvent.LogoutReason.USER))
    }

    /** Вызывается из [com.example.matcheckmobile.data.remote.net.TokenAuthenticator]. */
    fun notifySessionInvalidated(triggeredByPath: String, httpCode: Int) {
        logoutAuditLog.record(
            reason = "REFRESH_INVALID",
            lastPath = triggeredByPath,
            lastCode = httpCode,
        )
        _sessionEvents.tryEmit(SessionEvent.LoggedOut(reason = SessionEvent.LogoutReason.REFRESH_INVALID))
    }

    enum class RefreshOutcome { Ok, NoRefresh, Invalid, NetworkError }

    private fun mapLoginError(error: Throwable): LoginException {
        // Барьер смены аккаунта — не сетевая ошибка, а осознанный отказ:
        // прокидываем как отдельный код, чтобы UI предложил синхронизацию.
        if (error is AccountSwitchBlocked) {
            return LoginException(LoginError.UnsentDataOnDevice(error.pending))
        }
        if (error is AccountWipeFailed) {
            return LoginException(LoginError.WipeIncomplete(error.outcome.failedFiles))
        }
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

        /** Для журнала: по нему видно, что сессию погасил проактивный refresh со сплеша. */
        const val REFRESH_PATH = "/api/v1/auth/refresh"
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

    /**
     * Вход другим аккаунтом отклонён: на планшете осталась неотправленная
     * работа прошлого инспектора. Несём [pending] целиком, а не строку —
     * UI должен показать тот же диалог с выбором «отправить / удалить и
     * войти / отмена», иначе после автоматического разлогина пользователь
     * упирается в текст без единой кнопки.
     */
    data class UnsentDataOnDevice(
        val pending: PendingWork,
    ) : LoginError()

    /** Очистка не завершилась — вход отменён, чтобы данные не осиротели. */
    data class WipeIncomplete(val failedFiles: Int) : LoginError()
    data class Unknown(val message: String?) : LoginError()
}

class LoginException(val error: LoginError) : RuntimeException(error.toString())
