package com.example.matcheckmobile.data.repository

import android.content.Context
import com.example.matcheckmobile.data.remote.update.AppUpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Скачивает APK во внутренний external-files-каталог (`updates/`) с
 * прогрессом и опциональной sha256-верификацией.
 *
 * Каталог объявлен в res/xml/file_paths.xml через external-files-path —
 * FileProvider потом раздаст оттуда content://-URI системному installer'у.
 *
 * Отдельный OkHttpClient с длинным таймаутом записи (5 мин) — APK может
 * быть 30 МБ на медленной мобильной сети.
 */
class AppUpdateDownloader(private val appContext: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * Качает APK по [AppUpdateManifest.apkUrl]. [onProgress] — колбэк
     * с целым процентом [0..100], вызывается из IO-dispatcher'а.
     *
     * При наличии [AppUpdateManifest.apkSha256] после скачивания файл
     * верифицируется; при несовпадении хэшей возвращает Failure и удаляет
     * битый файл.
     */
    /**
     * Качает APK с авто-ретраями: GitHub release-CDN
     * (objects.githubusercontent.com) периодически отдаёт обрыв/таймаут, из-за
     * чего раньше установку приходилось перезапускать вручную по многу раз.
     * Делаем до [MAX_ATTEMPTS] попыток с короткой паузой; наружу отдаём ошибку
     * только если все попытки провалились. SHA-mismatch тоже ретраим — это, как
     * правило, недокачанный файл.
     */
    suspend fun download(
        manifest: AppUpdateManifest,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = downloadOnce(manifest, onProgress)
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull()
            if (attempt < MAX_ATTEMPTS - 1) {
                onProgress(0)
                delay(RETRY_DELAY_MS)
            }
        }
        Result.failure(lastError ?: IllegalStateException("Не удалось скачать обновление"))
    }

    private suspend fun downloadOnce(
        manifest: AppUpdateManifest,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = File(appContext.getExternalFilesDir(null), "updates").apply {
                if (!exists()) mkdirs()
                // Чистим прошлые APK — место беречь, плюс избегаем путаницы
                // если файл со старого fail остался.
                listFiles()?.forEach { it.delete() }
            }
            val targetFile = File(targetDir, "matcheck-${manifest.versionName}.apk")

            val request = Request.Builder().url(manifest.apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty body")
                val totalBytes = body.contentLength().takeIf { it > 0 }
                    ?: manifest.apkSizeBytes
                    ?: -1L

                body.source().use { source ->
                    targetFile.sink().buffer().use { sink ->
                        var downloaded = 0L
                        var lastReported = -1
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = source.read(buffer, 64 * 1024L)
                            if (read == -1L) break
                            sink.write(buffer, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastReported) {
                                    onProgress(percent)
                                    lastReported = percent
                                }
                            }
                        }
                        sink.flush()
                    }
                }
            }

            // sha256-проверка по желанию (manifest может не содержать)
            val expected = manifest.apkSha256
            if (!expected.isNullOrBlank()) {
                val actual = sha256(targetFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    targetFile.delete()
                    error("sha256 mismatch: expected=$expected actual=$actual")
                }
            }
            onProgress(100)
            targetFile
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r == -1) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1500L
    }
}
