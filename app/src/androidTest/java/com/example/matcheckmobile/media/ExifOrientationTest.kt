package com.example.matcheckmobile.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Проверка [normalizeExifOrientationInPlace]: покрывает EXIF-варианты (в т.ч.
 * отражения), сброс тега в NORMAL и — главное — БЕЗОПАСНОСТЬ ДАННЫХ: при
 * no-op / битом входе оригинал не меняется, временный файл не остаётся.
 *
 * Инструментальный тест: нужны настоящие Bitmap/ExifInterface/Os, поэтому
 * гоняется на устройстве/эмуляторе (connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class ExifOrientationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // ── helpers ──────────────────────────────────────────────────────────

    private fun leftRight(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
            drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.BLUE })
        }
        return b
    }

    private fun topBottom(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawRect(0f, 0f, w.toFloat(), h / 2f, Paint().apply { color = Color.RED })
            drawRect(0f, h / 2f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.BLUE })
        }
        return b
    }

    private fun writeJpeg(bmp: Bitmap): File {
        val f = File.createTempFile("exif", ".jpg", ctx.cacheDir)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
    }

    private fun setOrientation(f: File, orientation: Int) {
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }

    private fun orientationOf(f: File): Int =
        ExifInterface(f.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

    private fun dims(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    private fun tmpOf(f: File) = File(f.parentFile, "${f.name}.rot.tmp")

    /** Доминирует ли красный над синим в точке (JPEG-допуск). */
    private fun isReddish(f: File, x: Int, y: Int): Boolean {
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        val p = bmp.getPixel(x, y)
        bmp.recycle()
        return Color.red(p) > Color.blue(p) + 40
    }

    // ── повороты: размеры + сброс тега ───────────────────────────────────

    @Test
    fun rotate90SwapsDimensionsAndResetsTag() {
        val f = writeJpeg(leftRight(60, 40))
        setOrientation(f, ExifInterface.ORIENTATION_ROTATE_90)

        normalizeExifOrientationInPlace(f)

        assertEquals("90° меняет местами стороны", 40 to 60, dims(f))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationOf(f))
        assertFalse("временный файл не остаётся", tmpOf(f).exists())
        f.delete()
    }

    @Test
    fun rotate270SwapsDimensions() {
        val f = writeJpeg(leftRight(60, 40))
        setOrientation(f, ExifInterface.ORIENTATION_ROTATE_270)

        normalizeExifOrientationInPlace(f)

        assertEquals(40 to 60, dims(f))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationOf(f))
        f.delete()
    }

    @Test
    fun rotate180FlipsContentAndResetsTag() {
        // Верх красный, низ синий. После 180° верх обязан стать синим.
        val f = writeJpeg(topBottom(40, 60))
        setOrientation(f, ExifInterface.ORIENTATION_ROTATE_180)

        normalizeExifOrientationInPlace(f)

        assertEquals("180° размеры не меняет", 40 to 60, dims(f))
        assertFalse("верх больше не красный", isReddish(f, 20, 8))
        assertTrue("низ стал красным", isReddish(f, 20, 52))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationOf(f))
        f.delete()
    }

    @Test
    fun flipHorizontalMirrorsAndResetsTag() {
        // Лево красное, право синее. После горизонтального отражения лево — синее.
        val f = writeJpeg(leftRight(60, 40))
        setOrientation(f, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)

        normalizeExifOrientationInPlace(f)

        assertEquals(60 to 40, dims(f))
        assertFalse("лево больше не красное", isReddish(f, 8, 20))
        assertTrue("право стало красным", isReddish(f, 52, 20))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, orientationOf(f))
        f.delete()
    }

    // ── безопасность данных ──────────────────────────────────────────────

    @Test
    fun normalTagIsByteForByteNoOp() {
        val f = writeJpeg(leftRight(60, 40))
        setOrientation(f, ExifInterface.ORIENTATION_NORMAL)
        val before = f.readBytes()

        normalizeExifOrientationInPlace(f)

        assertArrayEquals("NORMAL → файл не тронут", before, f.readBytes())
        assertFalse(tmpOf(f).exists())
        f.delete()
    }

    @Test
    fun undefinedTagIsByteForByteNoOp() {
        // JPEG от Bitmap.compress без EXIF-тега → UNDEFINED → no-op.
        val f = writeJpeg(leftRight(60, 40))
        val before = f.readBytes()

        normalizeExifOrientationInPlace(f)

        assertArrayEquals(before, f.readBytes())
        f.delete()
    }

    @Test
    fun corruptFileIsUntouched() {
        val f = File.createTempFile("bad", ".jpg", ctx.cacheDir).apply { writeText("not a jpeg at all") }
        val before = f.readBytes()

        normalizeExifOrientationInPlace(f)

        assertArrayEquals("битый вход → оригинал байт-в-байт цел", before, f.readBytes())
        assertFalse(tmpOf(f).exists())
        f.delete()
    }

    @Test
    fun emptyFileIsNoOp() {
        val f = File.createTempFile("empty", ".jpg", ctx.cacheDir)
        assertEquals(0L, f.length())

        normalizeExifOrientationInPlace(f)

        assertEquals(0L, f.length())
        assertFalse(tmpOf(f).exists())
        f.delete()
    }
}
