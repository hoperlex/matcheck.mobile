package com.example.matcheckmobile.media

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Небольшой кэш декодированных миниатюр в памяти.
 *
 * Нужен из-за `LazyRow`: при выходе плитки за экран composable уничтожается
 * вместе со своим `remember`, и при возврате кадр декодировался заново, а для
 * чужих фото — заново скачивался. На списке архива это заметно.
 *
 * Держит только миниатюры плиток, полноразмерные кадры сюда не кладём — они
 * тяжёлые и открываются по одному.
 */
object PhotoFrameCache {

    private const val MAX_ENTRIES = 64

    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    private fun key(photoId: String, thumb: Boolean) = "$photoId.${if (thumb) "t" else "m"}"

    fun get(photoId: String, thumb: Boolean): ImageBitmap? =
        if (thumb) cache.get(key(photoId, thumb)) else null

    fun put(photoId: String, thumb: Boolean, image: ImageBitmap) {
        if (thumb) cache.put(key(photoId, thumb), image)
    }

    /** Очистка при смене аккаунта: кадры чужого пользователя не должны пережить logout. */
    fun clear() = cache.evictAll()
}
