package com.example.matcheckmobile.presentation.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
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

    val startScan = {
        val activity = context.findActivity()
        if (activity == null) {
            onError("Нет активити для запуска сканера")
        } else {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(20)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build()
            // Маркер варианта для диагностики чёрной полосы: UI сканера живёт в
            // Play services и обновляется сам, поэтому в logcat рядом с нашей
            // строкой видно, какой режим запрошен и какой DynamiteModule подъехал.
            Log.i(TAG, "scanner mode=BASE (вариант C)")
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

    // Сканер сам разруливает CAMERA-permission; нам нужно только LOCATION
    // для последующего проставления координат в водяной знак.
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

private const val TAG = "DocScanner"

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
