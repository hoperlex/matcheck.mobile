package com.example.matcheckmobile.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.matcheckmobile.media.PhotoStorage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Запускает Google ML Kit Document Scanner — экран камеры с детектом краёв
 * листа, авто-кадром, перспективным выпрямлением и многостраничной съёмкой.
 * Каждая отснятая страница копируется в локальное хранилище [PhotoStorage]
 * и абсолютный путь отдаётся через [onPageCaptured] (вызов на страницу).
 *
 * Требует Google Play Services на устройстве — модуль сканера подтянется
 * автоматически при первом вызове, поэтому первый запуск может занять
 * 5-10 секунд.
 */
@Composable
fun rememberDocumentScanner(
    photoStorage: PhotoStorage,
    onPageCaptured: (String) -> Unit,
    onError: (String) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages.orEmpty()
            if (pages.isEmpty()) {
                onError("Сканер не вернул страниц")
                return@rememberLauncherForActivityResult
            }
            pages.forEach { page ->
                runCatching {
                    val file = photoStorage.importFromUri(page.imageUri, prefix = "doc")
                    onPageCaptured(file.absolutePath)
                }.onFailure { t ->
                    onError(t.message ?: "Не удалось сохранить страницу")
                }
            }
        }
    }

    return {
        val activity = context.findActivity()
        if (activity == null) {
            onError("Нет активити для запуска сканера")
        } else {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(20)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            val client = GmsDocumentScanning.getClient(options)
            client.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener { e ->
                    onError(e.message ?: "Не удалось открыть сканер документов")
                }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
