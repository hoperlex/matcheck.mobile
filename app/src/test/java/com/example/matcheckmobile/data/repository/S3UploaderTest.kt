package com.example.matcheckmobile.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Socket
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Поведение PUT в S3 при зависании и отмене.
 *
 * Инцидент, ради которого написано: 30.07 приёмка пролежала на планшете 16
 * часов при живой сети. Рабочая гипотеза — блокирующий PUT без общего таймаута
 * держал sync-мьютекс, а `syncOnce` идёт целиком под ним, поэтому вставала вся
 * синхронизация до перезапуска процесса.
 *
 * Таймауты в тестах короткие и инъектируются: с боевыми тремя минутами проверка
 * шла бы три минуты реального времени.
 */
class S3UploaderTest {

    private val servers = mutableListOf<FakeS3Server>()

    @After
    fun tearDown() = servers.forEach(FakeS3Server::close)

    /**
     * Главная защита от регрессии: боевой клиент обязан иметь `callTimeout`.
     *
     * Именно его отсутствие делало зависание возможным. Пооперационные
     * connect/read/write вызов целиком не ограничивают — сервер, который
     * понемногу принимает байты, укладывается в каждый из них.
     */
    @Test
    fun `боевой клиент ограничивает вызов целиком`() {
        val client = S3Uploader.defaultClient()
        assertEquals(Duration.ofMinutes(3).toMillis().toInt(), client.callTimeoutMillis)
        assertTrue("callTimeout должен быть строго больше нуля", client.callTimeoutMillis > 0)
    }

    /**
     * Сервер принимает соединение и молчит, НЕ нарушая пооперационные таймауты:
     * они заданы заведомо больше общего лимита. Значит оборвать вызов способен
     * только `callTimeout` — ровно то, чего раньше не было.
     */
    @Test
    fun `молчащий сервер обрывается по общему таймауту, а не по пооперационным`() {
        val server = fakeS3 { /* принимаем соединение и ничего не отвечаем */ }
        val uploader = S3Uploader(client(callTimeoutMs = 400, perOperationSec = 30))

        val startedAt = System.currentTimeMillis()
        val error = runBlocking {
            runCatching { uploader.put(server.url, tempFile(), "image/jpeg", "p1") }.exceptionOrNull()
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("ожидали InterruptedIOException, получили $error", error is InterruptedIOException)
        assertTrue("вызов должен оборваться по общему лимиту (~400мс), а не по 30с: ${elapsed}мс", elapsed < 5_000)
    }

    /**
     * Отмена корутины должна доходить до самого HTTP-вызова. Раньше здесь был
     * блокирующий `execute()`: он отмену не замечал, поток оставался занят, а
     * `withLock` не отпускал мьютекс до конца вызова.
     */
    @Test
    fun `отмена корутины прерывает загрузку, не дожидаясь таймаута`() {
        val server = fakeS3 { /* молчим, чтобы вызов гарантированно висел */ }
        // callTimeout заведомо больше времени теста: если отмена не сработает,
        // тест провалится по времени, а не «случайно пройдёт» из-за таймаута.
        val uploader = S3Uploader(client(callTimeoutMs = 30_000, perOperationSec = 30))

        var caught: Throwable? = null
        val startedAt = System.currentTimeMillis()
        runBlocking {
            val job = launch {
                try {
                    uploader.put(server.url, tempFile(), "image/jpeg", "p1")
                } catch (e: Throwable) {
                    caught = e
                }
            }
            delay(200)
            job.cancel()
            job.join()
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("ожидали CancellationException, получили $caught", caught is CancellationException)
        assertTrue("отмена должна отработать быстро, а не ждать таймаут: ${elapsed}мс", elapsed < 5_000)
    }

    /** Обычный успешный PUT ничего не ломает — поведение не изменилось. */
    @Test
    fun `успешный ответ завершает загрузку без ошибки`() {
        val server = fakeS3 { socket -> socket.writeRaw("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") }
        val uploader = S3Uploader(client(callTimeoutMs = 5_000, perOperationSec = 5))

        val error = runBlocking {
            runCatching { uploader.put(server.url, tempFile(), "image/jpeg", "p1") }.exceptionOrNull()
        }

        assertEquals(null, error)
    }

    /**
     * Тело ответа S3 несёт XML с причиной (SignatureDoesNotMatch, AccessDenied).
     * Без него неудачный PUT по одному коду не диагностируется.
     */
    @Test
    fun `неуспешный ответ отдаёт код и тело в сообщении`() {
        val body = "<Error><Code>AccessDenied</Code></Error>"
        val server = fakeS3 { socket ->
            socket.writeRaw("HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.length}\r\n\r\n$body")
        }
        val uploader = S3Uploader(client(callTimeoutMs = 5_000, perOperationSec = 5))

        val error = runBlocking {
            runCatching { uploader.put(server.url, tempFile(), "image/jpeg", "p1") }.exceptionOrNull()
        }

        assertTrue("ожидали IOException, получили $error", error is IOException)
        assertTrue("в сообщении нет кода: ${error?.message}", error?.message?.contains("403") == true)
        assertTrue("в сообщении нет тела: ${error?.message}", error?.message?.contains("AccessDenied") == true)
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private fun client(callTimeoutMs: Long, perOperationSec: Long) = OkHttpClient.Builder()
        .connectTimeout(perOperationSec, TimeUnit.SECONDS)
        .readTimeout(perOperationSec, TimeUnit.SECONDS)
        .writeTimeout(perOperationSec, TimeUnit.SECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** Мелкий файл: тело запроса должно уместиться в буфер сокета. */
    private fun tempFile(): File = File.createTempFile("s3-upload", ".jpg").apply {
        writeBytes(ByteArray(64) { 0x42 })
        deleteOnExit()
    }

    private fun fakeS3(handler: (Socket) -> Unit): FakeS3Server = FakeS3Server(handler).also { servers += it }

}
