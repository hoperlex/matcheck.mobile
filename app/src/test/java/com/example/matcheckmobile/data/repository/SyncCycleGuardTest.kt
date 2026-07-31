package com.example.matcheckmobile.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Семантика потолка на цикл синхронизации.
 *
 * Самое хрупкое место всего фикса: `TimeoutCancellationException` сам является
 * `CancellationException`, поэтому наивная обработка ломает либо retry воркера,
 * либо распространение отмены. Здесь это зафиксировано тестами.
 *
 * Главное свойство — **мьютекс освобождается**. Ради него всё и делалось:
 * 30.07 зависший под мьютексом цикл остановил синхронизацию планшета на 16
 * часов, до перезапуска приложения.
 */
class SyncCycleGuardTest {

    @Test
    fun `успешный цикл отдаёт результат и отпускает мьютекс`() = runBlocking {
        val mutex = Mutex()

        val result = withCycleTimeout(timeoutMs = 1_000, mutex = mutex) { Result.success(42) }

        assertEquals(42, result.getOrNull())
        assertFalse("мьютекс должен быть свободен", mutex.isLocked)
    }

    @Test
    fun `зависший цикл прерывается и освобождает мьютекс`() = runBlocking {
        val mutex = Mutex()
        var reportedElapsed = -1L

        val result = withCycleTimeout(
            timeoutMs = 200,
            mutex = mutex,
            onTimeout = { reportedElapsed = it },
        ) {
            delay(10_000) // «висящий» PUT
            Result.success(1)
        }

        assertTrue(
            "ожидали SyncCycleTimeoutException, получили ${result.exceptionOrNull()}",
            result.exceptionOrNull() is SyncCycleTimeoutException,
        )
        assertFalse("мьютекс обязан освободиться, иначе встанет вся синхронизация", mutex.isLocked)
        assertTrue("onTimeout должен получить длительность, было $reportedElapsed", reportedElapsed >= 200)
    }

    /**
     * Ключевое для воркера: собственный таймаут возвращается как `Result`, а не
     * летит наружу отменой. Иначе `MatcheckSyncWorker` не получил бы свой Result
     * и не назначил бы `Result.retry()` — цикл бы не повторился.
     */
    @Test
    fun `собственный таймаут не выходит наружу отменой`() = runBlocking {
        val mutex = Mutex()
        var escaped: Throwable? = null

        val result = runCatching {
            withCycleTimeout(timeoutMs = 150, mutex = mutex) {
                delay(10_000)
                Result.success(1)
            }
        }.onFailure { escaped = it }

        assertEquals("отмена не должна вылетать из функции", null, escaped)
        assertTrue(result.getOrNull()?.isFailure == true)
    }

    /** После прерванного цикла следующий стартует нормально. */
    @Test
    fun `следующий цикл после таймаута отрабатывает`() = runBlocking {
        val mutex = Mutex()

        withCycleTimeout(timeoutMs = 150, mutex = mutex) {
            delay(10_000)
            Result.success(1)
        }
        val second = withCycleTimeout(timeoutMs = 1_000, mutex = mutex) { Result.success("ok") }

        assertEquals("ok", second.getOrNull())
        assertFalse(mutex.isLocked)
    }

    /**
     * Внешняя отмена (снятие воркера) обязана распространяться, а не оседать
     * внутри `Result` как обычная ошибка.
     */
    @Test
    fun `внешняя отмена пробрасывается и отпускает мьютекс`() = runBlocking {
        val mutex = Mutex()
        var caught: Throwable? = null

        val job = launch {
            try {
                withCycleTimeout(timeoutMs = 60_000, mutex = mutex) {
                    delay(10_000)
                    Result.success(1)
                }
            } catch (e: Throwable) {
                caught = e
            }
        }
        delay(150)
        job.cancel()
        job.join()

        assertTrue("ожидали CancellationException, получили $caught", caught is CancellationException)
        assertFalse("мьютекс должен освободиться и при внешней отмене", mutex.isLocked)
    }

    /**
     * Отмена, пойманная внутрь `Result` вызываемым кодом (там `runCatching`
     * ловит Throwable), не должна маскироваться под ошибку синхронизации.
     */
    @Test
    fun `отмена внутри результата не маскируется под ошибку`() = runBlocking {
        val mutex = Mutex()
        var caught: Throwable? = null

        val job = launch {
            try {
                withCycleTimeout(timeoutMs = 60_000, mutex = mutex) {
                    // Имитируем runCatching в runCycle: отмена проглочена в Result.
                    runCatching {
                        delay(10_000)
                        1
                    }
                }
            } catch (e: Throwable) {
                caught = e
            }
        }
        delay(150)
        job.cancel()
        job.join()

        assertTrue("ожидали CancellationException, получили $caught", caught is CancellationException)
        assertFalse(mutex.isLocked)
    }

    /** Обычная ошибка цикла остаётся обычной ошибкой — поведение не изменилось. */
    @Test
    fun `ошибка внутри цикла возвращается как есть`() = runBlocking {
        val mutex = Mutex()
        val boom = IOException("нет сети")

        val result = withCycleTimeout(timeoutMs = 1_000, mutex = mutex) { Result.failure<Int>(boom) }

        assertEquals(boom, result.exceptionOrNull())
        assertFalse(mutex.isLocked)
    }
}
