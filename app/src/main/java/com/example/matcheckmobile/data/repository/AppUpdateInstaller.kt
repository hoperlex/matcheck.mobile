package com.example.matcheckmobile.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Запускает системный installer APK через ACTION_VIEW + FileProvider.
 *
 * Тонкости:
 *  - FLAG_GRANT_READ_URI_PERMISSION обязателен — иначе PackageInstaller не
 *    сможет прочитать APK через content://-URI.
 *  - FLAG_ACTIVITY_NEW_TASK нужен, если вызываем из не-Activity контекста
 *    (например, прямо после download'а из IO-coroutine с appContext).
 *  - Authority совпадает с тем, что прописан в AndroidManifest.xml:
 *    `${applicationId}.fileprovider`. В release-сборке это
 *    com.example.matcheckmobile.fileprovider, в debug —
 *    com.example.matcheckmobile.dev.fileprovider.
 *  - На Android 8+ установка APK требует, чтобы пользователь разрешил
 *    конкретно нашему приложению «Установка неизвестных приложений»
 *    (canRequestPackageInstalls). Без этого ACTION_VIEW молча ничего не
 *    делает — раньше это глоталось runCatching'ом и выглядело как «окно
 *    исчезло, обновление не ставится». Теперь проверяем явно и уводим
 *    пользователя в нужный системный экран.
 *
 * Сама установка дальше — обычный системный диалог Android. Пользователь
 * нажмёт «Установить», система проверит подпись (та же = update, разная =
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE), установит и опционально откроет
 * приложение заново.
 */
class AppUpdateInstaller {

    /**
     * Можно ли запускать установку APK без дополнительных действий. На API < 26
     * пер-пакетного разрешения нет (старая глобальная настройка «неизвестные
     * источники»), поэтому считаем, что можно.
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Открывает системный экран «Установка неизвестных приложений» для su10. */
    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Запускает системный installer APK. Возвращает Result.failure, если
     * запустить не удалось (нет PackageInstaller / startActivity упал) — чтобы
     * вызывающий показал ошибку, а не проглотил её молча.
     */
    fun launchInstaller(context: Context, apkFile: File): Result<Unit> = runCatching {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
