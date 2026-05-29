package com.example.matcheckmobile.data.remote.update

import kotlinx.serialization.Serializable

/**
 * Сериализованный манифест версии приложения, который лежит ассетом в
 * GitHub Releases (matcheck.mobile-releases/releases/latest/download/manifest.json).
 *
 * Минимальный «контракт» для in-app updater'а: клиент скачивает JSON,
 * сравнивает [versionCode] с BuildConfig.VERSION_CODE и, если новее,
 * качает APK по [apkUrl], сверяет sha256 и запускает системный installer.
 *
 * Все поля кроме обязательных трёх (versionCode, versionName, apkUrl)
 * имеют дефолты — поведение должно остаться корректным, если сервер
 * (publishGithubRelease task) добавит/уберёт поля. Json настроен с
 * ignoreUnknownKeys = true в AppUpdateFetcher.
 */
@Serializable
data class AppUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    // Размер APK в байтах. На клиенте используется только для UX (показать
    // «~30 МБ» в диалоге). DownloadManager сам определяет реальный размер
    // из HTTP Content-Length при скачивании.
    val apkSizeBytes: Long? = null,
    // Контрольная сумма скачанного APK. После загрузки клиент пересчитывает
    // sha256 файла и сравнивает с этим значением — защита от повреждения
    // во время скачивания. Если null — проверка пропускается.
    val apkSha256: String? = null,
    val changelog: String = "",
    // Минимальная versionCode, ниже которой клиент должен принудительно
    // обновиться (диалог без «Отложить»). На старте всегда 1 — никого не
    // принуждаем; в будущем можно поднимать, если выкатим критический фикс.
    val minSupportedVersionCode: Int = 1,
    val releasedAt: String? = null,
)
