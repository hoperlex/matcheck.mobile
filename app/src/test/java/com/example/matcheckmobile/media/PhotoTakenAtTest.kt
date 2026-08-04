package com.example.matcheckmobile.media

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * [photoTakenAtIso] — момент съёмки кадра для `photos.taken_at`.
 *
 * Регрессия, ради которой функция появилась: время фото ставил сервер при
 * presign, и на портале заголовок этапа показывал момент синхронизации
 * (04.08, ЖК ВАРШАВСКАЯ LIFE: съёмка 12:24–12:30, в карточке 12:31 у обоих
 * этапов). Брать `Instant.now()` в момент отправки нельзя — при офлайне это
 * повторило бы ту же ошибку уже на клиенте.
 */
class PhotoTakenAtTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fallback = Instant.parse("2026-08-04T09:31:14Z")

    @Test
    fun `берёт время последней записи файла`() {
        val shotAt = Instant.parse("2026-08-04T09:27:00Z")
        val file = tmp.newFile("cargo.jpg")
        check(file.setLastModified(shotAt.toEpochMilli()))

        val result = photoTakenAtIso(file) { fallback }

        assertEquals(shotAt.toString(), result)
    }

    @Test
    fun `недоступное время файла заменяет текущим, а не эпохой`() {
        // lastModified() == 0 отдаёт и несуществующий файл, и ФС без метки.
        val missing = tmp.root.resolve("нет-такого.jpg")

        val result = photoTakenAtIso(missing) { fallback }

        assertEquals(fallback.toString(), result)
    }

    @Test
    fun `время из будущего уходит как есть — срез делает сервер`() {
        // Часы планшета могли уехать. Клиент это не чинит: клампы живут в
        // domain/operations/confirmed-at.ts, чтобы правило было одно на всех.
        val ahead = Instant.parse("2030-01-01T00:00:00Z")
        val file = tmp.newFile("ahead.jpg")
        check(file.setLastModified(ahead.toEpochMilli()))

        val result = photoTakenAtIso(file) { fallback }

        assertEquals(ahead.toString(), result)
    }

    @Test
    fun `значение стабильно между вызовами, пока файл не переписан`() {
        val file = tmp.newFile("stable.jpg")
        check(file.setLastModified(Instant.parse("2026-08-04T09:27:00Z").toEpochMilli()))

        assertEquals(photoTakenAtIso(file) { fallback }, photoTakenAtIso(file) { fallback })
    }
}
