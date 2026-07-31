package com.example.matcheckmobile.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PUT снимка в S3 по presigned-ссылке.
 *
 * Вынесен из [PhotoUploadProcessor] отдельным классом ровно ради тестируемости:
 * здесь единственная зависимость — OkHttp, поэтому поведение таймаута и отмены
 * проверяется unit-тестом без Room и без сети.
 *
 * Два свойства, ради которых класс и появился:
 *
 * 1. **Вызов ограничен целиком** (`callTimeout`). Пооперационные
 *    connect/read/write этого не делают: соединение, которое понемногу
 *    принимает байты, укладывается в каждый из них и живёт сколько угодно.
 * 2. **Вызов отменяем**. Раньше здесь был блокирующий `execute()` в обычной
 *    функции — отмену корутины он не замечал. Снятый воркер оставлял живой
 *    HTTP-вызов, а тот держал sync-мьютекс (`syncOnce` идёт целиком под ним),
 *    и вся синхронизация вставала до перезапуска процесса.
 */
class S3Uploader(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Загружает [file] по presigned-[url].
     *
     * @param photoId только для логов — в них не должно быть самого URL.
     * @throws InterruptedIOException при срабатывании `callTimeout`.
     * @throws IOException при сетевой ошибке или неуспешном ответе S3.
     * @throws CancellationException при отмене корутины.
     */
    suspend fun put(url: String, file: File, contentType: String, photoId: String) {
        val request = Request.Builder()
            .url(url)
            .put(file.asRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        val host = request.url.host
        val startedAt = System.currentTimeMillis()
        // URL не логируем никогда — в presigned-ссылке подпись. Только хост,
        // id снимка и размер: этого хватает, чтобы опознать висящую загрузку.
        Log.i(TAG, "S3 PUT start · host=$host photo=$photoId bytes=${file.length()}")

        val call = client.newCall(request)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Тело ответа S3 несёт XML с <Code>/<Message>
                        // (SignatureDoesNotMatch, AccessDenied, NoSuchBucket).
                        // Без него неудачный PUT по одному HTTP-коду не
                        // диагностируется — особенно через прокси s3.cloud.ru,
                        // который возвращает 403/400 по разным причинам.
                        val failure = response.use { r ->
                            if (r.isSuccessful) {
                                null
                            } else {
                                val body = runCatching { r.body?.string()?.take(400) }.getOrNull()
                                IOException("S3 PUT $host failed: HTTP ${r.code}${body?.let { " · $it" } ?: ""}")
                            }
                        }
                        if (!cont.isActive) return
                        if (failure != null) {
                            Log.e(TAG, failure.message ?: "S3 PUT failed")
                            cont.resumeWithException(failure)
                        } else {
                            cont.resume(Unit)
                        }
                    }
                })
            }
        } catch (e: CancellationException) {
            // Внешняя отмена (снятие воркера, таймаут цикла). Пробрасываем как
            // есть: это не ошибка загрузки, и помечать снимок сбойным нельзя.
            Log.w(TAG, "S3 PUT cancelled · host=$host photo=$photoId после ${elapsed(startedAt)}мс")
            throw e
        } catch (e: InterruptedIOException) {
            // Так OkHttp сообщает о срабатывании callTimeout.
            Log.e(TAG, "S3 PUT timeout · host=$host photo=$photoId после ${elapsed(startedAt)}мс")
            throw e
        }
        Log.i(TAG, "S3 PUT ok · host=$host photo=$photoId за ${elapsed(startedAt)}мс")
    }

    private fun elapsed(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    companion object {
        const val TAG = "PhotoUpload"

        /**
         * Абсолютный потолок на PUT целиком.
         *
         * Три минуты с большим запасом: снимок сжимается примерно до 1.5 МБ
         * (см. RemotePhotoStorage). При срабатывании локальный файл остаётся на
         * месте, статус уходит в UPLOAD_ERROR и следующий цикл грузит заново —
         * потери данных нет, есть отложенная повторная попытка.
         */
        val CALL_TIMEOUT: Duration = Duration.ofMinutes(3)

        /** Plain OkHttpClient без auth-interceptor-ов — presigned URL уже подписан. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT)
            .build()
    }
}
