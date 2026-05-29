package com.example.matcheckmobile.data.repository

import android.content.Context
import android.content.Intent
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
 *
 * Сама установка дальше — обычный системный диалог Android. Пользователь
 * нажмёт «Установить», система проверит подпись (та же = update, разная =
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE), установит и опционально откроет
 * приложение заново.
 */
class AppUpdateInstaller {

    fun launchInstaller(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // runCatching на случай устройств без PackageInstaller (например,
        // некоторые специализированные сборки Android) — в этом случае
        // обновление просто не запустится, но ничего не упадёт.
        runCatching { context.startActivity(intent) }
    }
}
