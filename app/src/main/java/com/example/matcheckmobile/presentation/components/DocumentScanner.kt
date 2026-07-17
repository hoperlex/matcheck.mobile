package com.example.matcheckmobile.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.matcheckmobile.presentation.scanner.DocumentScanContract
import com.example.matcheckmobile.presentation.scanner.DocumentScanResult

private const val TAG = "DocumentScanner"

/**
 * Запускает собственный CameraX-сканер документов: полноэкранное превью, ручной
 * затвор, многостраничная съёмка. Каждая подтверждённая страница отдаётся через
 * [onPageCaptured] абсолютным путём, в порядке съёмки.
 *
 * Раньше здесь был Google ML Kit Document Scanner — он не давал сделать съёмку
 * ручной (в его API нет setCaptureMode) и рисовал чёрную половину экрана на
 * части планшетов. Замороженная копия лежит в [rememberMlKitDocumentScanner]
 * как rollback.
 *
 * Геолокация не запрашивается: на страницы документов водяной знак не
 * накладывается (он перекрывал текст УПД), поэтому координаты здесь не нужны —
 * достаточно CAMERA.
 *
 * @param onPageCaptured вызывается на каждую подтверждённую страницу
 * @param onError        сбой камеры или отказ в разрешении — показать оператору
 */
@Composable
fun rememberDocumentScanner(
    onPageCaptured: (String) -> Unit,
    onError: (String) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(DocumentScanContract()) { result ->
        when (result) {
            is DocumentScanResult.Success -> result.paths.forEach(onPageCaptured)

            is DocumentScanResult.Failure -> {
                // Логируем всегда: Sentry-DSN пока не задан, и logcat — наш
                // единственный канал разбора сбоя с боевого планшета.
                Log.e(TAG, "Document scan failed: ${result.message}")
                onError(result.message)
            }

            // Оператор вышел сам — это не ошибка, молчим.
            DocumentScanResult.Cancelled -> Unit
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(Unit)
        } else {
            Log.w(TAG, "CAMERA permission denied")
            onError("Нет доступа к камере")
        }
    }

    return {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            scanLauncher.launch(Unit)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
