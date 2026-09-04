package com.example.matcheckmobile.media

import android.util.Log
import com.example.matcheckmobile.data.repository.PhotoFetcher
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "PhotoBytesLoader"

private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 800L

/**
 * Скачивание кадра по presigned-URL с таймаутами, повторами и дисковым кэшем.
 *
 * Заменяет голый `URL(url).openStream()` в превью: у него не было ни одного
 * таймаута (у `HttpURLConnection` они по умолчанию бесконечные) и ни одного
 * повтора, поэтому единственная осечка сети навсегда превращала миниатюру в
 * «фото недоступно» — ровно то, на что жаловались с планшета.
 *
 * Клиент намеренно голый, как [com.example.matcheckmobile.data.repository.S3Uploader]:
 * presigned-URL уже подписан, а общий бизнес-клиент навесил бы `Authorization`
 * на любой хост ([com.example.matcheckmobile.data.remote.net.AuthHeaderInterceptor])
 * и сломал бы подпись, плюс Sentry-интерцептор утёк бы самой подписью в
 * breadcrumbs.
 */
class PhotoBytesLoader(
    private val photoFetcher: PhotoFetcher,
    private val cache: PhotoDiskCache,
    private val client: OkHttpClient = defaultClient(),
) {

    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Возвращает байты кадра или null, если добыть его не удалось.
     *
     * Single-flight: параллельные запросы одного кадра (а `LazyRow` их
     * устраивает легко) сериализуются по ключу, и второй забирает уже
     * скачанное из кэша, а не качает повторно.
     */
    suspend fun load(photoId: String, thumb: Boolean): ByteArray? {
        val key = cache.key(photoId, thumb)
        cache.get(key)?.let { cached -> runCatching { cached.readBytes() }.getOrNull()?.let { return it } }

        return keyLocks.getOrPut(key) { Mutex() }.withLock {
            cache.get(key)?.let { cached ->
                runCatching { cached.readBytes() }.getOrNull()?.let { return@withLock it }
            }
            fetch(photoId, thumb)?.also { cache.put(key, it) }
        }
    }

    private suspend fun fetch(photoId: String, thumb: Boolean): ByteArray? {
        var signatureRefreshed = false
        repeat(MAX_ATTEMPTS) { attempt ->
            val url = photoFetcher.getDisplayUrl(photoId, thumb = thumb).getOrNull()
                ?: return null
            when (val outcome = get(url)) {
                is Outcome.Ok -> return outcome.bytes
                // Объекта нет — повторять бессмысленно, это не «пока не доехало».
                is Outcome.Gone -> {
                    Log.w(TAG, "кадр отсутствует в хранилище · photo=$photoId thumb=$thumb")
                    return null
                }
                // Протухшая подпись. Общий классификатор считает 403 терминальным,
                // но здесь он означает «URL устарел»: сбрасываем кэш ссылок и
                // пробуем ровно один раз с новой подписью.
                is Outcome.Forbidden -> {
                    if (signatureRefreshed) return null
                    signatureRefreshed = true
                    photoFetcher.invalidate(photoId)
                }
                // Сеть или 5xx — транзиентно, ретраим.
                is Outcome.Transient -> {
                    if (attempt == MAX_ATTEMPTS - 1) {
                        Log.w(TAG, "кадр не скачался · photo=$photoId: ${outcome.reason}")
                        return null
                    }
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        return null
    }

    private sealed interface Outcome {
        class Ok(val bytes: ByteArray) : Outcome
        object Gone : Outcome
        object Forbidden : Outcome
        class Transient(val reason: String) : Outcome
    }

    /** URL не логируем никогда — в нём подпись; только хост и код. */
    private suspend fun get(url: String): Outcome {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(Outcome.Transient(e.javaClass.simpleName))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = response.use { r ->
                            when {
                                r.isSuccessful ->
                                    runCatching { Outcome.Ok(r.body!!.bytes()) }
                                        .getOrElse { Outcome.Transient("body") }
                                r.code == 404 || r.code == 410 -> Outcome.Gone
                                r.code == 403 -> Outcome.Forbidden
                                r.code >= 500 -> Outcome.Transient("HTTP ${r.code}")
                                else -> Outcome.Gone
                            }
                        }
                        if (cont.isActive) cont.resume(outcome)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Transient(e.javaClass.simpleName)
        }
    }

    companion object {
        /**
         * Голый клиент без интерцепторов, с ограничением вызова целиком:
         * пооперационных таймаутов мало — соединение, понемногу принимающее
         * байты, укладывается в каждый из них и живёт сколько угодно.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
