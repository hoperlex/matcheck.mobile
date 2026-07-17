package com.example.matcheckmobile.presentation.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.matcheckmobile.media.PhotoStorage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * ROLLBACK-КОД. Не используется и пути к нему из UI нет.
 *
 * Оставлен, чтобы вернуть ML Kit одним коммитом, если CameraX-сканер поведёт
 * себя неожиданно на боевых планшетах. Заменён на [rememberDocumentScanner]
 * (CameraX) по трём причинам, каждая из которых для ML Kit нерешаема:
 *
 *  1. Ручная съёмка. В GmsDocumentScannerOptions.Builder нет setCaptureMode() —
 *     константы CAPTURE_MODE_AUTO/MANUAL объявлены, но применить их нечем.
 *     Проверено по артефакту 16.0.0; новее версии в Google Maven нет.
 *  2. Чёрная половина экрана на Samsung SM-X115: layout сканера — часть UI
 *     Google Play Services, снаружи не настраивается.
 *  3. Зависимость от Play Services: модуль docscan_full качается по требованию
 *     и на части планшетов не приезжает.
 *
 * Код намеренно заморожен в исходном виде (включая ныне ненужный запрос
 * геолокации), чтобы откат был точным.
 */
@Composable
fun rememberMlKitDocumentScanner(
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

    val startScan = {
        val activity = context.findMlKitActivity()
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

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> startScan() }

    return {
        val locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (locationGranted) {
            startScan()
        } else {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
}

private fun Context.findMlKitActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
