package com.example.matcheckmobile.monitoring

import android.app.Application
import com.example.matcheckmobile.BuildConfig
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Инициализация Sentry. Вызывается ПЕРВОЙ строкой в [MatcheckApplication.onCreate],
 * до AppContainer — чтобы ловить и ранние падения инициализации.
 *
 * No-op, если DSN пустой (в debug BuildConfig.SENTRY_DSN="" → события не шлём;
 * release без вставленного DSN — тоже тихо). Так dev/непрод не засоряют Sentry.
 *
 * Безопасность (проект строг к ПДн/секретам):
 *  - isSendDefaultPii=false — заголовки/куки/тело по умолчанию НЕ уходят;
 *  - beforeSend дополнительно зануляет заголовки/куки и режет query-строку из URL
 *    (в т.ч. presigned-подписи X-Amz-*);
 *  - в наши ручные captureException/captureMessage мы кладём только теги/ид/категорию,
 *    без токенов, ФИО, телефона, госномера, тела ответа.
 */
fun initSentry(app: Application) {
    val dsn = BuildConfig.SENTRY_DSN
    if (dsn.isBlank()) return

    SentryAndroid.init(app) { options ->
        options.dsn = dsn
        options.environment = if (BuildConfig.DEBUG) "debug" else "production"
        options.release = "matcheck.mobile@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        // Глобального UncaughtExceptionHandler в приложении нет — Sentry закрывает пробел.
        options.isEnableUncaughtExceptionHandler = true
        options.isSendDefaultPii = false

        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
            event.request?.let { req ->
                req.headers = null
                req.cookies = null
                req.url = stripQuery(req.url)
            }
            event
        }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
            (crumb.data["url"] as? String)?.let { url ->
                stripQuery(url)?.let { clean -> crumb.setData("url", clean) }
            }
            crumb
        }
    }
}

/** Убирает query-строку из URL (в т.ч. presigned-подписи S3). */
private fun stripQuery(url: String?): String? {
    if (url == null) return null
    val q = url.indexOf('?')
    return if (q == -1) url else url.substring(0, q)
}
