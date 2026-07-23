package com.example.matcheckmobile.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.matcheckmobile.media.PhotoStorage
import java.io.File

/**
 * Возвращает функцию-запускатель съёмки фото. При первом вызове запрашивает runtime-разрешение
 * CAMERA (объявлено в манифесте — без runtime-grant ACTION_IMAGE_CAPTURE падает с SecurityException),
 * затем создаёт временный файл, превращает в content URI и запускает системную камеру.
 *
 * @param onPhotoTaken вызывается с абсолютным путём файла, если съёмка успешна и файл непустой
 * @param onError      вызывается при отказе в разрешении, сбое запуска или пустом снимке
 * @param requestLocation просить ли LOCATION (нужна только для водяного знака). Для фото без штампа
 *   (документы) передать `false` — тогда запрашивается только CAMERA, без лишнего запроса геолокации.
 */
@Composable
fun rememberPhotoCapture(
    photoStorage: PhotoStorage,
    onPhotoTaken: (String) -> Unit,
    onError: (String) -> Unit = {},
    requestLocation: Boolean = true,
): () -> Unit {
    val context = LocalContext.current
    // Saveable: система может убить процесс, пока открыта камера. rememberSaveable вернёт путь
    // после воссоздания, и результат TakePicture (его хранит ActivityResultRegistry) не потеряется.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPath
        pendingPath = null
        when {
            // Отмена/сбой камеры — временный .jpg был создан пустым, убираем за собой.
            !success -> path?.let { runCatching { File(it).delete() } }
            path == null -> onError("Снимок потерян")
            // Камера отчиталась об успехе, но ничего не записала — доверять нельзя.
            File(path).length() <= 0L -> {
                runCatching { File(path).delete() }
                onError("Пустой снимок")
            }
            else -> onPhotoTaken(path)
        }
    }

    val launchCamera = {
        var created: File? = null
        try {
            val file = photoStorage.createTempFile().apply { if (!exists()) createNewFile() }
            created = file
            pendingPath = file.absolutePath
            cameraLauncher.launch(photoStorage.toContentUri(file))
        } catch (t: Throwable) {
            pendingPath = null
            created?.let { runCatching { it.delete() } }
            onError(t.message ?: "Не удалось открыть камеру")
        }
    }

    // Просим CAMERA (обязательное) и, если нужно, LOCATION (для водяного знака). Если LOCATION
    // отказали — снимок всё равно делаем, штамп позже напишет «GPS: нет».
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Состояние читаем заново, а не из grants: незапрошенных разрешений в карте нет вовсе.
        // Если CAMERA уже выдан, а LOCATION отклонён, запрос уходит только за LOCATION — и
        // grants[CAMERA] == null приняло бы уже выданную камеру за отказ, заблокировав съёмку.
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) launchCamera() else onError("Нет доступа к камере")
    }

    return {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        // Когда локация не нужна (документы) — считаем её «выданной», чтобы не запрашивать.
        val locationGranted = !requestLocation || run {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        }

        if (cameraGranted && locationGranted) {
            launchCamera()
        } else {
            val toRequest = buildList {
                if (!cameraGranted) add(Manifest.permission.CAMERA)
                if (requestLocation && !locationGranted) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }.toTypedArray()
            permissionLauncher.launch(toRequest)
        }
    }
}
