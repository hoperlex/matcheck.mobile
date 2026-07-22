package com.example.matcheckmobile.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Проверка скан-фильтра OpenCV (warp + adaptiveThreshold). Нужна нативная
 * библиотека, поэтому это instrumentation-тест, а не JVM.
 */
@RunWith(AndroidJUnit4::class)
class DocumentPageProcessorTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = OpenCvDocumentPageProcessor()

    @Before
    fun requireOpenCv() {
        assertTrue("OpenCV не загрузился", OpenCvBootstrap.isAvailable())
    }

    /** Цветной «документ»: белый лист с чёрным текстом и красной печатью. */
    private fun sourceJpeg(): File {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(40f, 40f, 360f, 70f, Paint().apply { color = Color.BLACK })
            drawCircle(320f, 240f, 30f, Paint().apply { color = Color.RED })
        }
        val f = File.createTempFile("src", ".jpg", ctx.cacheDir)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
    }

    private fun fullQuad() = NormalizedQuad(
        QuadPoint(0f, 0f), QuadPoint(1f, 0f), QuadPoint(1f, 1f), QuadPoint(0f, 1f),
    )

    @Test
    fun cropProducesDecodableGrayscaleScan() = runBlocking {
        val src = sourceJpeg()
        val target = File.createTempFile("out", ".jpg", ctx.cacheDir)

        val ok = processor.cropToQuad(src, target, fullQuad())
        assertTrue("обрезка+фильтр должны отработать", ok)

        val out = BitmapFactory.decodeFile(target.absolutePath)
        assertTrue("результат декодируется", out != null && out.width > 0 && out.height > 0)

        // Скан-фильтр даёт grayscale: во всех пикселях R≈G≈B. Проверяем сетку.
        var grayViolations = 0
        for (x in 0 until out!!.width step 37) {
            for (y in 0 until out.height step 37) {
                val p = out.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (abs(r - g) > 4 || abs(g - b) > 4) grayViolations++
            }
        }
        assertTrue("выход должен быть grayscale (R≈G≈B), нарушений: $grayViolations", grayViolations == 0)

        out.recycle()
        src.delete(); target.delete()
    }

    @Test
    fun failedCropDeletesTarget() = runBlocking {
        // Пустой/битый исходник → cropToQuad должен вернуть false и убрать target.
        val src = File.createTempFile("bad", ".jpg", ctx.cacheDir).apply { writeText("not a jpeg") }
        val target = File.createTempFile("out", ".jpg", ctx.cacheDir)

        val ok = processor.cropToQuad(src, target, fullQuad())

        assertTrue("на битом входе обрезка не удаётся", !ok)
        assertTrue("target удалён при неудаче", !target.exists())
        src.delete()
    }
}
