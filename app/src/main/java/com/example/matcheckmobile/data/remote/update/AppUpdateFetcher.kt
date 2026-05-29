package com.example.matcheckmobile.data.remote.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches manifest.json from GitHub Releases and parses it. Uses an
 * OkHttpClient separate from the main API client:
 *
 *  - no TokenAuthenticator (manifest is public, no Bearer needed);
 *  - no CookieJar or other matcheck interceptors;
 *  - short timeouts — manifest is ~1KB, hanging longer than 10 sec makes
 *    no sense. The APK itself is downloaded separately via OkHttp in
 *    AppUpdateDownloader with longer timeouts.
 *
 * GitHub `/releases/latest/download/...` returns a 302 redirect to the
 * actual tag. OkHttp follows redirects by default, no special config needed.
 */
class AppUpdateFetcher(private val manifestUrl: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun fetch(): Result<AppUpdateManifest> = withContext(Dispatchers.IO) {
        if (manifestUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("manifest URL is empty"))
        }
        runCatching {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP " + response.code)
                }
                val body = response.body?.string() ?: error("empty body")
                json.decodeFromString(AppUpdateManifest.serializer(), body)
            }
        }
    }
}
