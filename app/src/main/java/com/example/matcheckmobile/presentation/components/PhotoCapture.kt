package com.example.matcheckmobile.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.matcheckmobile.media.PhotoStorage

/**
 * Возвращает функцию-запускатель съёмки фото. При первом вызове запрашивает runtime-разрешение
 * CAMERA (объявлено в манифесте — без runtime-grant ACTION_IMAGE_CAPTURE падает с SecurityException),
 * затем создаёт временный файл, превращает в content URI и запускает системную камеру.
 *
 * @param onPhotoTaken вызывается с абсолютным путём файла, если съёмка успешна
 * @param onError      вызывается при отказе в разрешении или сбое запуска камеры
 */
@Composable
fun rememberPhotoCapture(
    photoStorage: PhotoStorage,
    onPhotoTaken: (String) -> Unit,
    onError: (String) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    var pendingPath by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPath
        pendingPath = null
        if (success && path != null) onPhotoTaken(path)
    }

    val launchCamera = {
        try {
            val file = photoStorage.createTempFile().apply { if (!exists()) createNewFile() }
            pendingPath = file.absolutePath
            cameraLauncher.launch(photoStorage.toContentUri(file))
        } catch (t: Throwable) {
            pendingPath = null
            onError(t.message ?: "Не удалось открыть камеру")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera() else onError("Нет доступа к камере")
    }

    return {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
