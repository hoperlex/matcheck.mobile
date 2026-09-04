package com.example.matcheckmobile.media

import com.example.matcheckmobile.data.remote.api.PhotosApi
import com.example.matcheckmobile.data.remote.api.dto.PhotoConfirmResponse
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignRequest
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignResponse
import com.example.matcheckmobile.data.remote.api.dto.PhotoUrlResponse
import com.example.matcheckmobile.data.repository.FakeS3Server
import com.example.matcheckmobile.data.repository.PhotoFetcher
import com.example.matcheckmobile.data.repository.writeBody
import com.example.matcheckmobile.data.repository.writeRaw
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скачивание кадров из S3.
 *
 * Ради чего написано: раньше миниатюра тянулась голым `URL(url).openStream()` —
 * без единого таймаута, без повторов и с проглоченной ошибкой. Одна осечка сети
 * навсегда превращала фото в «недоступно», пока инспектор не выйдет с экрана.
 */
class PhotoBytesLoaderTest {

    private val servers = mutableListOf<FakeS3Server>()
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        servers.forEach(FakeS3Server::close)
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun server(handler: (Socket) -> Unit) = FakeS3Server(handler).also { servers += it }

    private fun cacheDir(): File =
        Files.createTempDirectory("frames").toFile().also { tempDirs += it }

    /** Отдаёт всегда один и тот же URL; считает, сколько раз его спросили. */
    private class FakeApi(private val url: String) : PhotosApi {
        val urlCalls = AtomicInteger(0)
        override suspend fun presign(body: PhotoPresignRequest): PhotoPresignResponse = error("не нужен")
        override suspend fun confirm(id: String): PhotoConfirmResponse = error("не нужен")
        override suspend fun delete(id: String) = error("не нужен")
        override suspend fun url(id: String, thumb: Boolean?): PhotoUrlResponse {
            urlCalls.incrementAndGet()
            return PhotoUrlResponse(url = url, expiresIn = 300)
        }
    }

    private fun loader(server: FakeS3Server, dir: File = cacheDir()): Pair<PhotoBytesLoader, FakeApi> {
        val api = FakeApi(server.url)
        return PhotoBytesLoader(PhotoFetcher(api), PhotoDiskCache(dir)) to api
    }

    @Test
    fun `успешный кадр скачивается и кладётся в кэш`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val srv = server { it.writeBody("200 OK", payload) }
        val dir = cacheDir()
        val (loader, _) = loader(srv, dir)

        val bytes = loader.load("photo-1", thumb = true)

        assertArrayEquals(payload, bytes)
        assertNotNull("кадр обязан осесть в кэше", dir.listFiles()?.firstOrNull())
    }

    @Test
    fun `второй запрос берётся из кэша, а не из сети`() = runBlocking {
        val srv = server { it.writeBody("200 OK", byteArrayOf(9, 9)) }
        val dir = cacheDir()
        val (loader, _) = loader(srv, dir)

        loader.load("photo-1", thumb = true)
        val connectionsAfterFirst = srv.connections.get()
        loader.load("photo-1", thumb = true)

        assertEquals("повторного похода в сеть быть не должно", connectionsAfterFirst, srv.connections.get())
    }

    @Test
    fun `пятисотка ретраится`() = runBlocking {
        val payload = byteArrayOf(7)
        val srv = server { socket ->
            // Первые две попытки — 500, третья отдаёт кадр.
            if (srv0Attempts.incrementAndGet() < 3) {
                socket.writeRaw("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            } else {
                socket.writeBody("200 OK", payload)
            }
        }
        val (loader, _) = loader(srv)

        val bytes = loader.load("photo-1", thumb = true)

        assertArrayEquals(payload, bytes)
        assertTrue("должно быть несколько попыток", srv.connections.get() >= 3)
    }

    private val srv0Attempts = AtomicInteger(0)

    @Test
    fun `404 не ретраится — объекта просто нет`() = runBlocking {
        val srv = server {
            it.writeRaw("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
        val (loader, _) = loader(srv)

        assertNull(loader.load("photo-1", thumb = true))
        assertEquals("повторять поход за отсутствующим объектом незачем", 1, srv.connections.get())
    }

    @Test
    fun `403 обновляет подпись и повторяет ровно один раз`() = runBlocking {
        val srv = server {
            it.writeRaw("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
        val (loader, api) = loader(srv)

        assertNull(loader.load("photo-1", thumb = true))
        // Протухшая подпись: кэш URL сбрасывается и ссылка запрашивается заново.
        assertEquals("ссылку обязаны перезапросить", 2, api.urlCalls.get())
        assertEquals(2, srv.connections.get())
    }

    @Test
    fun `параллельные запросы одного кадра качают его один раз`() = runBlocking {
        val srv = server { it.writeBody("200 OK", byteArrayOf(4, 2)) }
        val (loader, _) = loader(srv)

        val results = (1..4).map { async { loader.load("photo-1", thumb = true) } }.awaitAll()

        results.forEach { assertArrayEquals(byteArrayOf(4, 2), it) }
        assertEquals("single-flight: сеть дёргаем однажды", 1, srv.connections.get())
    }

    @Test
    fun `миниатюра и оригинал кэшируются раздельно`() = runBlocking {
        val srv = server { it.writeBody("200 OK", byteArrayOf(1)) }
        val dir = cacheDir()
        val (loader, _) = loader(srv, dir)

        loader.load("photo-1", thumb = true)
        loader.load("photo-1", thumb = false)

        assertEquals("это разные байты одного photoId", 2, dir.listFiles()?.size)
    }

    @Test
    fun `чтение из кэша обновляет время доступа`() {
        val dir = cacheDir()
        val cache = PhotoDiskCache(dir)
        cache.put(cache.key("photo-1", thumb = true), byteArrayOf(1))
        val file = File(dir, cache.key("photo-1", thumb = true))
        file.setLastModified(1_000L)

        cache.get(cache.key("photo-1", thumb = true))

        // Без обновления времени LRU выбросил бы как раз то, чем пользуются.
        assertTrue("время доступа обязано обновиться", file.lastModified() > 1_000L)
    }

    @Test
    fun `кэш вытесняет самые давно не открывавшиеся`() {
        val dir = cacheDir()
        val cache = PhotoDiskCache(dir, maxBytes = 100)
        val old = ByteArray(60)
        cache.put(cache.key("old", thumb = true), old)
        File(dir, cache.key("old", thumb = true)).setLastModified(1_000L)
        cache.put(cache.key("new", thumb = true), ByteArray(60))

        assertTrue("свежий кадр остаётся", File(dir, cache.key("new", thumb = true)).exists())
        assertTrue("старый вытеснен", !File(dir, cache.key("old", thumb = true)).exists())
    }
}
