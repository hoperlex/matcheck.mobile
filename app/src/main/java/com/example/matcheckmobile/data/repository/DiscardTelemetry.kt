package com.example.matcheckmobile.data.repository

import android.util.Log
import com.example.matcheckmobile.data.local.entity.MutationEntity
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.security.MessageDigest

/**
 * Телеметрия факта отмены конфликтной мутации при terminal server-win.
 *
 * ВАЖНО: содержимое payload НЕ отправляем (позиции/комментарии/госномер/
 * водитель — рабочие данные). Держим ту же политику, что существующий Drop-
 * репорт: только теги + метаданные, «без сырого тела» (см. MutationProcessor
 * Drop-ветка). Server-win — осознанная потеря конфликтной старой правки;
 * телеметрия фиксирует ФАКТ (тип/операция/версия/размер/SHA-256) для
 * мониторинга, а не восстановимость.
 *
 * ВНИМАНИЕ: Sentry сейчас DSN-gated и в проде ВЫКЛЮЧЕН (`SENTRY_DSN=""` в
 * build.gradle) — `Sentry.captureMessage` там no-op. Поэтому дублируем факт в
 * `Log.w` (локальный logcat-след). До настройки боевого DSN «телеметрия»
 * доступна только через logcat/adb.
 */
object DiscardTelemetry {
    private const val TAG = "DiscardTelemetry"

    /** no-op — для JVM-тестов, чтобы Android Log/Sentry не ломали чистый тест. */
    val noop: (List<MutationEntity>) -> Unit = { }

    val sentry: (List<MutationEntity>) -> Unit = { discarded ->
        discarded.forEach { m ->
            Log.w(
                TAG,
                "auto server-win discard: ${m.entityType} ${m.operation} ${m.entityId} " +
                    "baseVersion=${m.baseVersion ?: -1} payloadSize=${m.payloadJson?.length ?: 0}",
            )
            Sentry.withScope { scope ->
                scope.setTag("entityType", m.entityType)
                scope.setTag("operation", m.operation)
                scope.setTag("reason", "auto_server_win_terminal")
                scope.setLevel(SentryLevel.WARNING)
                scope.setExtra("baseVersion", (m.baseVersion ?: -1).toString())
                scope.setExtra("payloadSize", (m.payloadJson?.length ?: 0).toString())
                scope.setExtra("payloadSha256", sha256(m.payloadJson.orEmpty()))
                Sentry.captureMessage("auto server-win: local edits discarded")
            }
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
