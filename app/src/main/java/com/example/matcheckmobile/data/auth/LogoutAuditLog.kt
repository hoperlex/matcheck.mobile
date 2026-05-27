package com.example.matcheckmobile.data.auth

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Локальный журнал событий разлогина. Пишется в `cacheDir/logout_history.log`
 * по одной строке на инцидент. Назначение — отлаживать «почему сессию выкинуло»
 * на полевом устройстве, когда нет прямого доступа к логам через ADB:
 * последний инцидент можно вытянуть через Settings → служебное меню или
 * `adb shell run-as com.example.matcheckmobile cat cache/logout_history.log`.
 *
 * Формат строки: ISO-8601 timestamp\treason=<...>\tpath=<...>\tcode=<...>.
 * Файл усекается до последних 200 строк когда вырастает > 64KB.
 */
class LogoutAuditLog(appContext: Context) {

    private val file: File = File(appContext.cacheDir, FILE_NAME)

    @Synchronized
    fun record(reason: String, lastPath: String? = null, lastCode: Int? = null) {
        runCatching {
            val ts = Instant.now().toString()
            val line = "$ts\treason=$reason\tpath=${lastPath ?: "-"}\tcode=${lastCode ?: "-"}\n"
            file.appendText(line)
            if (file.length() > MAX_BYTES) {
                val tail = file.readLines().takeLast(KEEP_LINES)
                file.writeText(tail.joinToString("\n") + "\n")
            }
        }
    }

    fun readAll(): String = runCatching { file.readText() }.getOrDefault("")

    private companion object {
        const val FILE_NAME = "logout_history.log"
        const val MAX_BYTES = 64L * 1024L
        const val KEEP_LINES = 200
    }
}
