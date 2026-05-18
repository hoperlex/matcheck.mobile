package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.remote.api.PhotosApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Лёгкий кэш presigned-GET URL'ов для отображения фото в UI.
 * TTL 5 минут на сервере, мы храним 4 минуты с запасом — лучше переспросить,
 * чем словить 403/expired в Coil/Glide.
 */
class PhotoFetcher(
    private val photosApi: PhotosApi,
) {

    private val cache = ConcurrentHashMap<String, CachedUrl>()
    private val mutex = Mutex()

    /**
     * @param thumb true → запросить thumb-URL.
     */
    suspend fun getDisplayUrl(photoId: String, thumb: Boolean = false): Result<String> = runCatching {
        val key = "$photoId:${if (thumb) "t" else "m"}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return@runCatching it.url }

        mutex.withLock {
            cache[key]?.takeIf { it.expiresAtMs > now }?.let { return@withLock it.url }
            val r = photosApi.url(photoId, thumb = thumb)
            cache[key] = CachedUrl(
                url = r.url,
                // expiresIn в секундах от сервера; вычитаем 60с буфер.
                expiresAtMs = now + (r.expiresIn.coerceAtMost(CACHE_MAX_TTL_SEC) - CACHE_SAFETY_BUFFER_SEC) * 1000,
            )
            r.url
        }
    }

    fun invalidate(photoId: String) {
        cache.remove("$photoId:m")
        cache.remove("$photoId:t")
    }

    private data class CachedUrl(val url: String, val expiresAtMs: Long)

    private companion object {
        const val CACHE_MAX_TTL_SEC = 240L
        const val CACHE_SAFETY_BUFFER_SEC = 30L
    }
}
