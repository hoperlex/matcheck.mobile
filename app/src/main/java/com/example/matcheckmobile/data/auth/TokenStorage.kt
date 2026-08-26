package com.example.matcheckmobile.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * То, что нужно [com.example.matcheckmobile.data.remote.net.TokenRefreshCoordinator]
 * от хранилища, — и ничего сверх.
 *
 * Отдельный интерфейс не ради абстракции как таковой: [TokenStorage] тянет за собой
 * Android-контекст и EncryptedSharedPreferences, то есть проверяться может только на
 * устройстве. Логика обновления токена — самая гоночная часть правки, и её тесты обязаны
 * быть быстрыми, иначе их перестанут гонять.
 */
interface SessionTokens {
    val accessToken: String?
    fun isAccessTokenValid(marginMillis: Long = TokenStorage.ACCESS_VALID_MARGIN_MS): Boolean
    fun sessionKey(): TokenStorage.SessionKey
    fun saveRefreshedTokensIfSessionMatches(
        expected: TokenStorage.SessionKey,
        accessToken: String,
        accessExpiresInSec: Long,
        refreshToken: String?,
        refreshExpiresInSec: Long?,
    ): Boolean
    fun clearIfSessionMatches(expected: TokenStorage.SessionKey): Boolean
}

/**
 * Хранит токены и профиль текущего сеанса. Refresh-token обязан лежать
 * в EncryptedSharedPreferences (Jetpack Security), access-token достаточно
 * хранить там же — пережить рестарт процесса проще.
 *
 * Жизненный цикл: при логине пишем всё, при logout / invalid_refresh — clear().
 */
class TokenStorage(context: Context) : SessionTokens {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    override val accessToken: String? get() = _state.value.accessToken
    val refreshToken: String? get() = _state.value.refreshToken

    fun isAuthenticated(): Boolean = _state.value.refreshToken != null

    /**
     * Жив ли ещё access-token с запасом [marginMillis]. Используется на cold
     * start, чтобы НЕ дёргать проактивный /auth/refresh, когда access и так
     * валиден: лишняя ротация refresh-токена на каждый запуск приводила к
     * гонке с фоновым sync/SSE и reuse-detection 401 → ложный разлогин при
     * простом закрытии-открытии приложения.
     */
    override fun isAccessTokenValid(marginMillis: Long): Boolean {
        val expiresAt = _state.value.accessExpiresAt ?: return false
        return expiresAt - marginMillis > System.currentTimeMillis()
    }

    /** Полный набор данных после успешного login. */
    @Synchronized
    fun saveSession(
        accessToken: String,
        accessExpiresInSec: Long,
        refreshToken: String,
        refreshExpiresInSec: Long,
        userId: String,
        userEmail: String,
        role: String,
        siteId: String?,
    ) {
        val now = System.currentTimeMillis()
        // commit(), а не apply(): сервер уже зафиксировал ротацию refresh,
        // и если процесс умрёт до сброса на диск (OOM, установка нового APK,
        // ребут), при следующем старте мобила прочитает старый refresh →
        // сервер увидит reuse → kill сессии. commit() блокирует возврат до
        // фактического fsync — токены и серверное состояние всегда консистентны.
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, now + accessExpiresInSec * 1000L)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_REFRESH_EXPIRES_AT, now + refreshExpiresInSec * 1000L)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, userEmail)
            .putString(KEY_ROLE, role)
            .putString(KEY_SITE_ID, siteId)
            // last-known-good переживает транзиентный blank siteId (напр. пустой
            // /me), чтобы списки этапов/архива не гасли. Пишем только non-blank;
            // на logout всё стирается clear() → чужой объект не утечёт.
            .also { if (!siteId.isNullOrBlank()) it.putString(KEY_LAST_GOOD_SITE_ID, siteId) }
            .commit()
        _state.value = readSnapshot()
    }

    /**
     * Обновление siteId без перезаписи токенов. Используется, когда
     * админ поменял объект инспектора на сервере и мы узнаём об этом из
     * /auth/me (см. SyncRepository.syncOnce). Без этого штамп на фото
     * 1-го Этапа (Stage1FormViewModel.resolveSiteName читает siteId из
     * tokenStorage) показывал бы старый объект до полного logout/login.
     * Возвращает true, если значение действительно изменилось.
     */
    @Synchronized
    fun updateSiteId(newSiteId: String?): Boolean {
        if (_state.value.siteId == newSiteId) return false
        prefs.edit()
            .putString(KEY_SITE_ID, newSiteId)
            .also { if (!newSiteId.isNullOrBlank()) it.putString(KEY_LAST_GOOD_SITE_ID, newSiteId) }
            .commit()
        _state.value = readSnapshot()
        return true
    }

    /** Обновление пары токенов после успешного /auth/refresh. */
    @Synchronized
    fun saveRefreshedTokens(
        accessToken: String,
        accessExpiresInSec: Long,
        refreshToken: String?,
        refreshExpiresInSec: Long?,
    ) {
        val now = System.currentTimeMillis()
        // commit() обязателен: см. комментарий в saveSession. Особенно
        // критично для refresh — сервер ротирует токен и инвалидирует
        // старый. Гонка между сетевым ответом и .apply()-ом на диск
        // приводила к «вылетам после обновления APK».
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, now + accessExpiresInSec * 1000L)
        if (refreshToken != null && refreshExpiresInSec != null) {
            editor
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_REFRESH_EXPIRES_AT, now + refreshExpiresInSec * 1000L)
        }
        editor.commit()
        _state.value = readSnapshot()
    }

    /**
     * Снимок «какая сессия сейчас» — берётся ДО сетевого запроса и предъявляется
     * при записи результата (см. [saveRefreshedTokensIfSessionMatches]).
     */
    override fun sessionKey(): SessionKey = _state.value.let { SessionKey(it.refreshToken, it.userId) }

    /**
     * Записать обновлённые токены, только если сессия не сменилась.
     *
     * Зачем отдельный метод, а не проверка на стороне вызывающего. Пока идёт
     * `/auth/refresh`, пользователь успевает выйти или войти под другой учёткой,
     * и поздний ответ записал бы токены поверх ЧУЖОЙ сессии. Сравнить снаружи и
     * потом вызвать [saveRefreshedTokens] недостаточно: между сравнением и
     * записью остаётся зазор, в который и попадает login/logout. Здесь проверка
     * и запись идут под одним замком, поэтому зазора нет.
     *
     * @return false — сессия сменилась, ничего не записано.
     */
    @Synchronized
    override fun saveRefreshedTokensIfSessionMatches(
        expected: SessionKey,
        accessToken: String,
        accessExpiresInSec: Long,
        refreshToken: String?,
        refreshExpiresInSec: Long?,
    ): Boolean {
        if (sessionKey() != expected) return false
        saveRefreshedTokens(accessToken, accessExpiresInSec, refreshToken, refreshExpiresInSec)
        return true
    }

    /**
     * Погасить сессию, только если она не сменилась, — зеркало
     * [saveRefreshedTokensIfSessionMatches]. Без этого отказ по СТАРОМУ
     * refresh-токену выкидывал бы пользователя из уже начатой НОВОЙ сессии.
     *
     * @return false — сессия сменилась, ничего не стёрто.
     */
    @Synchronized
    override fun clearIfSessionMatches(expected: SessionKey): Boolean {
        if (sessionKey() != expected) return false
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _state.value = readSnapshot()
    }

    /**
     * Что считаем «той же сессией»: пара «действующий refresh-token + пользователь».
     * Refresh-token меняется при каждой ротации, userId — при смене учётки;
     * вместе они ловят оба способа увести сессию из-под незавершённого запроса.
     */
    data class SessionKey(val refreshToken: String?, val userId: String?)

    private fun readSnapshot(): Snapshot = Snapshot(
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, null),
        accessExpiresAt = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L).takeIf { it > 0 },
        refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
        refreshExpiresAt = prefs.getLong(KEY_REFRESH_EXPIRES_AT, 0L).takeIf { it > 0 },
        userId = prefs.getString(KEY_USER_ID, null),
        userEmail = prefs.getString(KEY_USER_EMAIL, null),
        role = prefs.getString(KEY_ROLE, null),
        siteId = prefs.getString(KEY_SITE_ID, null),
        lastKnownGoodSiteId = prefs.getString(KEY_LAST_GOOD_SITE_ID, null),
    )

    data class Snapshot(
        val accessToken: String?,
        val accessExpiresAt: Long?,
        val refreshToken: String?,
        val refreshExpiresAt: Long?,
        val userId: String?,
        val userEmail: String?,
        val role: String?,
        val siteId: String?,
        /** Последний непустой siteId сессии; переживает транзиентный blank. */
        val lastKnownGoodSiteId: String? = null,
    ) {
        /**
         * siteId для UI-фильтров: активный, а если он транзиентно пуст —
         * последний известный хороший (в рамках той же сессии). Смена
         * пользователя стирает всё (clear()), так что чужой объект не утечёт.
         */
        val effectiveSiteId: String? get() = siteId?.ifBlank { null } ?: lastKnownGoodSiteId
    }

    companion object {
        // 60 сек запаса: если access истекает в ближайшую минуту — лучше
        // обновить заранее, чем словить 401 на первом же бизнес-запросе.
        const val ACCESS_VALID_MARGIN_MS = 60_000L
        const val FILE_NAME = "matcheck_secure_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_ROLE = "role"
        const val KEY_SITE_ID = "site_id"
        const val KEY_LAST_GOOD_SITE_ID = "last_good_site_id"
    }
}
