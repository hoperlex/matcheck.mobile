package com.example.matcheckmobile.data.remote.net

import com.example.matcheckmobile.data.auth.SessionTokens
import com.example.matcheckmobile.data.remote.auth.AuthApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

/**
 * Единственная точка обновления access-токена в приложении.
 *
 * Зачем понадобилась. Обновлять токен умели три места, и они не знали друг о
 * друге: у [TokenAuthenticator] был приватный мьютекс, `AuthRepository.refreshNow`
 * работал вообще без замка (его спасала только проверка «access ещё жив»), а
 * SSE не умел обновлять вовсе — свой OkHttp-клиент он строит без
 * [TokenAuthenticator]. Последнее и оказалось дорогим: канал уведомлений ложился
 * на 401 и не вставал, пока кто-нибудь другой случайно не обновит токен. Замер
 * 26.08: 137 устройств, максимум 843 секунды без уведомлений — то самое «иногда
 * приёмка не появляется», на которое жаловались инспекторы.
 *
 * Почему одного мьютекса мало и что здесь на самом деле защищает.
 *
 *  * **Двойная проверка под замком.** Пока вызывающий стоял в очереди, токен мог
 *    обновить кто-то другой. Отдаём готовый вместо второго `/auth/refresh` —
 *    именно это, а не сам замок, предотвращает повторное использование одного
 *    refresh-токена. Сервер считает такой повтор атакой, гасит сессию и отвечает
 *    401; в коде `refreshNow` этот инцидент описан как уже случившийся.
 *  * **Барьер смены сессии.** Мьютекс держит параллельные обновления, но не
 *    держит пользователя: за время запроса можно выйти и войти под другой
 *    учёткой. Поэтому ключ сессии снимается ДО запроса и предъявляется при
 *    записи — атомарно, внутри [TokenStorage] (см. `saveRefreshedTokensIfSessionMatches`).
 *
 * Правило, которому подчинён весь класс: **любой сбой вырождается в прежнее
 * поведение, а не в худшее.** Сеть упала — возвращаем [Outcome.NetworkError] и
 * никого не разлогиниваем; сессия сменилась — молча отдаём [Outcome.SessionChanged]
 * и ничего не пишем. Гасим сессию только по явному 401 от сервера и только ту,
 * которая этот 401 получила.
 */
class TokenRefreshCoordinator(
    private val tokenStorage: SessionTokens,
    /**
     * API на ОТДЕЛЬНОМ клиенте — без [TokenAuthenticator], но с теми же пинами и
     * заголовками. Так `/auth/refresh` не уходит через клиент, поток которого в
     * этот момент заблокирован внутри `Authenticator.authenticate`: при пачке
     * одновременных 401 это грозило исчерпанием потоков и пула соединений.
     * Заодно реентерабельность исключена по построению, а не по соглашению.
     */
    private val refreshApiProvider: () -> AuthApi,
    /** Вызывается РОВНО ОДИН раз на погашенную сессию — журнал и переход на логин. */
    private val onSessionInvalidated: (triggeredByPath: String, httpCode: Int) -> Unit,
) {

    private val mutex = Mutex()

    sealed interface Outcome {
        /** Токен пригоден — можно повторять запрос или открывать поток. */
        data class Fresh(val accessToken: String) : Outcome

        /**
         * Обновляться нечем: refresh-токена нет. Это НЕ разлогин — гасить нечего,
         * события не публикуем. Splash по этому исходу уходит на экран входа.
         */
        data object NoSession : Outcome

        /** Сервер отверг refresh. Сессия погашена, событие отправлено один раз. */
        data object Invalid : Outcome

        /**
         * Пока шёл запрос, сессию сменили (вход под другой учёткой или выход).
         * Результат отброшен. Вызывающий обязан НЕ повторять свой прежний запрос:
         * он принадлежал старой сессии, и отправлять его с новым токеном значило
         * бы выполнить действие одного пользователя от имени другого.
         */
        data object SessionChanged : Outcome

        /** Сеть недоступна. Сессия цела, повторим позже. */
        data object NetworkError : Outcome
    }

    /**
     * Вернуть пригодный access-токен, обновив его при необходимости.
     *
     * @param staleToken токен, который вызывающий видел негодным; `null` означает
     *   «просто дай пригодный». Нужен, чтобы отличить «мой токен протух» от
     *   «пока я ждал, кто-то уже всё обновил».
     * @param path путь запроса, инициировавшего цепочку, — только для журнала.
     */
    suspend fun obtainFresh(staleToken: String?, path: String): Outcome = mutex.withLock {
        val current = tokenStorage.accessToken
        if (current != null && current != staleToken && tokenStorage.isAccessTokenValid()) {
            return@withLock Outcome.Fresh(current)
        }

        val expected = tokenStorage.sessionKey()
        val refresh = expected.refreshToken ?: return@withLock Outcome.NoSession

        try {
            val r = refreshApiProvider().refresh("Bearer $refresh")
            val saved = tokenStorage.saveRefreshedTokensIfSessionMatches(
                expected = expected,
                accessToken = r.accessToken,
                accessExpiresInSec = r.expiresIn,
                refreshToken = r.refreshToken,
                refreshExpiresInSec = r.refreshExpiresIn,
            )
            if (saved) Outcome.Fresh(r.accessToken) else Outcome.SessionChanged
        } catch (e: HttpException) {
            if (e.code() == 401) {
                // Гасим только ту сессию, которая получила отказ. Если за время
                // запроса вошли заново, clearIfSessionMatches вернёт false — и
                // новая сессия останется жить, а событие не уйдёт.
                if (tokenStorage.clearIfSessionMatches(expected)) {
                    onSessionInvalidated(path, 401)
                    Outcome.Invalid
                } else {
                    Outcome.SessionChanged
                }
            } else {
                Outcome.NetworkError
            }
        } catch (_: IOException) {
            Outcome.NetworkError
        } catch (_: Throwable) {
            Outcome.NetworkError
        }
    }
}
