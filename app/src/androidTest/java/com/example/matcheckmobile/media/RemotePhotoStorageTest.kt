package com.example.matcheckmobile.media

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * [RemotePhotoStorage.prepareFromUri] — что реально уезжает на портал.
 *
 * Проверяет то, что JVM-тестом не доказать: итоговые габариты (2048 по длинной
 * стороне), отсутствие апскейла мелкого кадра и целостность SHA-256 — они живут
 * внутри `scaleToMaxSide`/`writeJpeg`, а не в `chooseSampleSize`.
 *
 * Входы — готовые JPEG из `androidTest/assets`, а не собранные в тесте bitmap'ы:
 * кадр 4160×3120 в памяти это ≈52 МБ, и тест исказил бы ровно ту проверку
 * памяти, ради которой затевался. Ассеты лежат в тестовом APK, поэтому читаются
 * через `instrumentation.context`, а не `targetContext`.
 */
@RunWith(AndroidJUnit4::class)
class RemotePhotoStorageTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val storage = RemotePhotoStorage(ctx)

    // ── helpers ──────────────────────────────────────────────────────────

    private fun copyAsset(name: String): File {
        val dst = File.createTempFile("fixture_", ".jpg", ctx.cacheDir)
        instrumentation.context.assets.open(name).use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        return dst
    }

    private fun dims(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Гоняет фикстуру через prepareFromUri и убирает за собой в `finally` —
     * и исходник в cacheDir, и main/thumb в filesDir/remote_photos (их пути
     * детерминированы по photoId, поэтому чистятся даже если подготовка упала).
     */
    private fun withPrepared(asset: String, block: (RemotePhotoStorage.PreparedPhoto) -> Unit) {
        val src = copyAsset(asset)
        val photoId = "androidtest-${UUID.randomUUID()}"
        val photosDir = File(ctx.filesDir, "remote_photos")
        try {
            block(storage.prepareFromUri(Uri.fromFile(src), photoId))
        } finally {
            src.delete()
            File(photosDir, "$photoId.jpg").delete()
            File(photosDir, "$photoId.thumb.jpg").delete()
        }
    }

    /** Общие инварианты: файлы непустые, размеры и SHA-256 соответствуют содержимому. */
    private fun assertConsistent(p: RemotePhotoStorage.PreparedPhoto) {
        val mainBytes = p.mainFile.readBytes()
        assertTrue("main пустой", mainBytes.isNotEmpty())
        assertEquals(mainBytes.size.toLong(), p.mainSizeBytes)
        assertEquals(sha256Hex(mainBytes), p.mainSha256Hex)

        val thumb = requireNotNull(p.thumbFile) { "thumb не создан" }
        val thumbBytes = thumb.readBytes()
        assertTrue("thumb пустой", thumbBytes.isNotEmpty())
        assertEquals(thumbBytes.size.toLong(), p.thumbSizeBytes)
        assertEquals(sha256Hex(thumbBytes), p.thumbSha256Hex)
        assertEquals("image/jpeg", p.contentType)
    }

    // ── габариты ─────────────────────────────────────────────────────────

    // Регрессия: до правки такой кадр уезжал на портал как 1040×780.
    @Test
    fun photo4160DecodesToFullLimit() {
        withPrepared("4160x3120.jpg") { p ->
            assertEquals("main должен быть ровно 2048 по длинной стороне", 2048 to 1536, dims(p.mainFile))
            assertEquals(320 to 240, dims(p.thumbFile!!))
            assertConsistent(p)
        }
    }

    // Главная проверка памяти: 4000 px попадает на inSampleSize=1, то есть
    // декодируется целиком (≈48 МБ) — на слабом планшете это худший случай.
    @Test
    fun photo4000DecodesWithoutSamplingAndFitsLimit() {
        withPrepared("4000x3000.jpg") { p ->
            assertEquals(2048 to 1536, dims(p.mainFile))
            assertEquals(320 to 240, dims(p.thumbFile!!))
            assertConsistent(p)
        }
    }

    // Апскейла нет: кадр мельче лимита уходит как есть, качество не выдумываем.
    @Test
    fun smallPhotoIsNotUpscaled() {
        withPrepared("1600x1200.jpg") { p ->
            assertEquals("мелкий кадр не растягиваем", 1600 to 1200, dims(p.mainFile))
            assertEquals(320 to 240, dims(p.thumbFile!!))
            assertConsistent(p)
        }
    }
}
