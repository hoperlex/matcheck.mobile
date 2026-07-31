package com.example.matcheckmobile.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Ограничивает один цикл синхронизации по времени, не ломая распространение
 * отмены.
 *
 * Зачем. `syncOnce` выполняется целиком под sync-мьютексом, и до этого он не
 * был ограничен ничем: зависший внутри вызов (исторически — PUT фото в S3 без
 * `callTimeout`) держал мьютекс, и ВСЕ последующие синхронизации — периодика,
 * SSE-триггер, кнопка — просто ждали его до перезапуска процесса.
 *
 * Тонкость, ради которой это отдельная функция. `TimeoutCancellationException`
 * сам является `CancellationException`, поэтому:
 *
 * - `runCatching` внутри [block] ловит Throwable, а значит и отмену. Если
 *   оставить её внутри `Result`, собственный таймаут не долетит до обработчика,
 *   а внешняя отмена молча превратится в «ошибку синхронизации». Поэтому отмена
 *   из результата **пробрасывается** дальше;
 * - собственный таймаут ловится **снаружи** `withLock`: к этому моменту
 *   раскрутка уже отпустила мьютекс, и следующий цикл стартует с чистого листа;
 * - собственный таймаут возвращается как `Result.failure`, а НЕ пробрасывается:
 *   иначе воркер не получит свой `Result` и не назначит retry;
 * - внешняя отмена (снятие воркера) пробрасывается как есть.
 */
suspend fun <T> withCycleTimeout(
    timeoutMs: Long,
    mutex: Mutex,
    onTimeout: (elapsedMs: Long) -> Unit = {},
    block: suspend () -> Result<T>,
): Result<T> {
    val startedAt = System.currentTimeMillis()
    return try {
        withTimeout(timeoutMs) {
            mutex.withLock {
                val result = block()
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                result
            }
        }
    } catch (e: TimeoutCancellationException) {
        onTimeout(System.currentTimeMillis() - startedAt)
        Result.failure(SyncCycleTimeoutException(timeoutMs))
    }
}
