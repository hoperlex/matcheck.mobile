package com.example.matcheckmobile.data.repository

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Минимальный сокет-сервер вместо MockWebServer: нужен именно контроль над
 * «принимаем соединение и молчим», и лишняя тестовая зависимость ради этого не
 * нужна.
 *
 * Общий для тестов отправки ([S3UploaderTest]) и скачивания
 * ([PhotoBytesLoaderTest]) кадров.
 */
internal class FakeS3Server(handler: (Socket) -> Unit) {
    private val server = ServerSocket(0)
    private val stopped = AtomicBoolean(false)
    private val accepted = mutableListOf<Socket>()

    /** Сколько раз к серверу подключились — по нему видно ретраи и single-flight. */
    val connections = java.util.concurrent.atomic.AtomicInteger(0)

    val url: String get() = "http://127.0.0.1:${server.localPort}/object"

    init {
        thread(isDaemon = true) {
            while (!stopped.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                synchronized(accepted) { accepted += socket }
                thread(isDaemon = true) { runCatching { handler(socket) } }
            }
        }
    }

    fun close() {
        stopped.set(true)
        runCatching { server.close() }
        synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
    }
}

/** Пишет сырой HTTP-ответ в сокет. */
internal fun Socket.writeRaw(raw: String) {
    getOutputStream().write(raw.toByteArray())
    getOutputStream().flush()
}

/** Ответ с телом произвольных байт. */
internal fun Socket.writeBody(status: String, body: ByteArray) {
    val head = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    getOutputStream().write(head.toByteArray())
    getOutputStream().write(body)
    getOutputStream().flush()
}
