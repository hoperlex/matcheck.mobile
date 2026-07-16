package com.example.matcheckmobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhotoStorageNamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `быстрые последовательные вызовы дают уникальные файлы`() {
        val dir = tmp.newFolder("operation_photos")

        val files = (1..20).map { createUniquePhotoFile(dir, "doc") }

        assertEquals(20, files.map { it.absolutePath }.toSet().size)
        assertTrue(files.all { it.exists() })
        assertEquals(20, dir.listFiles()!!.size)
    }

    // Основной тест: последовательный может случайно пройти и на старом коде,
    // если между вызовами набегает больше миллисекунды.
    @Test
    fun `конкурентные вызовы дают уникальные файлы`() {
        val dir = tmp.newFolder("operation_photos")
        val pool = Executors.newFixedThreadPool(8)

        val files = try {
            pool.invokeAll((1..64).map { Callable { createUniquePhotoFile(dir, "doc") } })
                .map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertEquals(64, files.map { it.absolutePath }.toSet().size)
        assertEquals(64, dir.listFiles()!!.size)
    }

    // PhotoCapture зовёт createTempFile с дефолтным «op» — два символа, тогда как
    // File.createTempFile требует минимум три. Штамп в префиксе закрывает это.
    @Test
    fun `двухсимвольный префикс не роняет создание`() {
        val dir = tmp.newFolder("operation_photos")

        val file = createUniquePhotoFile(dir, "op")

        assertTrue(file.exists())
        assertTrue(file.name.startsWith("op_"))
        assertTrue(file.name.endsWith(".jpg"))
    }
}
