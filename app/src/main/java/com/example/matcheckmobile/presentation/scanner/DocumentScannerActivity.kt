package com.example.matcheckmobile.presentation.scanner

import android.content.Intent
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.PhotoThumb
import com.example.matcheckmobile.ui.theme.MatcheckmobileTheme

/**
 * Собственный сканер документов на CameraX.
 *
 * Почему отдельная Activity, а не экран в NavHost: MainActivity объявлена
 * `resizeableActivity="false"`, а её ориентацию реактивно переписывает подписка
 * на DataStore — камера внутри неё унаследовала бы леттербокс и вошла бы в гонку
 * за requestedOrientation. Здесь окно наше: своя ориентация, свой immersive.
 *
 * Ориентацию НЕ фиксируем в configChanges намеренно: при повороте Activity
 * пересоздаётся и CameraX биндится заново под новую геометрию. Иначе в
 * UseCaseGroup остался бы ViewPort, построенный под старый экран, и снимок
 * разошёлся бы с превью.
 */
class DocumentScannerActivity : ComponentActivity() {

    private val vm: DocumentScannerViewModel by viewModels {
        val container = (application as MatcheckApplication).container
        viewModelFactory {
            initializer {
                DocumentScannerViewModel(
                    photoStorage = container.photoStorage,
                    appScope = container.appScope,
                    handle = createSavedStateHandle(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Каждое создание Activity (в т.ч. после поворота и рестарта процесса)
        // закрывает незавершённый кадр: файл есть, но валидацию не прошёл.
        vm.onSessionStart()

        onBackPressedDispatcher.addCallback(this) { cancelAndFinish() }

        setContent {
            MatcheckmobileTheme {
                DocumentScannerScreen(
                    vm = vm,
                    onDone = ::finishWithPages,
                    onCancel = ::cancelAndFinish,
                    onFatal = ::handleFatal,
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Панели возвращаются после диалога разрешений/шторки — прячем снова.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        // setDecorFitsSystemWindows лишь разрешает рисовать под панелями —
        // скрывает их именно hide().
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun finishWithPages() {
        // Владение передаём ДО setResult: после этого ни onCleared, ни отмена
        // файлы не удалят.
        val pages = vm.takePagesForResult()
        val data = Intent().putStringArrayListExtra(
            DocumentScanContract.EXTRA_PAGES,
            ArrayList(pages),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cancelAndFinish() {
        vm.cancelSession()
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * Камера не поднялась. Если снятых страниц нет — отдаём Failure сразу.
     * Если есть — не закрываем экран и не удаляем их: пусть оператор решит,
     * вернуть снятое, повторить или выйти.
     */
    private fun handleFatal(message: String) {
        if (vm.state.value.pages.isEmpty()) {
            vm.cancelSession()
            setResult(
                DocumentScanContract.RESULT_FAILED,
                Intent().putExtra(DocumentScanContract.EXTRA_ERROR, message),
            )
            finish()
        } else {
            vm.onFatalError(message)
        }
    }
}

@Composable
private fun DocumentScannerScreen(
    vm: DocumentScannerViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onFatal: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var bindAttempt by remember { mutableIntStateOf(0) }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView) надёжнее уживается с Compose-overlay.
            // Выставляем строго до назначения surfaceProvider.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    DisposableEffect(previewView, lifecycleOwner, bindAttempt) {
        var provider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        var boundCapture: ImageCapture? = null
        var disposed = false

        // viewPort доступен только после layout — до этого UseCaseGroup собрать нельзя.
        previewView.doOnLayout {
            if (disposed) return@doOnLayout
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                // Activity могла умереть, пока провайдер поднимался.
                if (disposed) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        onFatal("На устройстве нет задней камеры")
                        return@addListener
                    }
                    val viewPort = previewView.viewPort
                        ?: run {
                            onFatal("Камера не смогла определить область кадра")
                            return@addListener
                        }
                    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

                    val preview = Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .apply { setSurfaceProvider(previewView.surfaceProvider) }

                    val capture = ImageCapture.Builder()
                        .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(rotation)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                // Downstream жмёт до 2048 и не апскейлит — ниже
                                // опускаться нельзя, иначе УПД станет нечитаемым.
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(2048, 1536),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()

                    // ViewPort связывает превью и захват с одной областью сенсора:
                    // без него снимок не совпадёт с тем, что видел оператор.
                    val group = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .build()

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group,
                    )
                    boundPreview = preview
                    boundCapture = capture
                    imageCapture = capture
                    vm.onCameraReady()
                } catch (t: Throwable) {
                    onFatal(t.message ?: "Не удалось запустить камеру")
                }
            }, ContextCompat.getMainExecutor(context))
        }

        onDispose {
            disposed = true
            imageCapture = null
            // Отвязываем свои use case'ы поимённо: unbindAll() снёс бы и чужую
            // сессию, если она появится в процессе.
            val useCases = listOfNotNull(boundPreview, boundCapture).toTypedArray()
            if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Превью на всё окно: никакого Column с отдельной нижней зоной и никакого
        // aspectRatio() — именно так у ML Kit и появлялась чёрная половина экрана.
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // Управление — оверлеем поверх камеры. Safe-insets только здесь: превью
        // они ужимать не должны.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.pages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.pages, key = { it }) { path ->
                        PhotoThumb(
                            filePath = path,
                            size = 64.dp,
                            onRemove = { vm.removePage(path) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("Отмена") }

                ShutterButton(
                    enabled = state.canCapture,
                    busy = state.captureInProgress,
                    onClick = {
                        val capture = imageCapture ?: return@ShutterButton
                        val ticket = vm.beginCapture() ?: return@ShutterButton
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(ticket.file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    vm.onFrameSaved(ticket)
                                }

                                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                    vm.onFrameFailed(
                                        ticket,
                                        exception.message ?: "Не удалось сделать снимок",
                                    )
                                }
                            },
                        )
                    },
                )

                Button(onClick = onDone, enabled = state.canFinish) {
                    Text("Готово (${state.pages.size})")
                }
            }
        }

        state.fatalError?.let { error ->
            FatalErrorOverlay(
                message = error,
                pageCount = state.pages.size,
                onDone = onDone,
                onRetry = {
                    vm.retryAfterFatal()
                    bindAttempt++
                },
                onCancel = onCancel,
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        // Затвор заблокирован и во время кадра, и пока камера не в ready —
        // иначе быстрый двойной тап даёт два кадра и сбитый порядок.
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(72.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * Камера умерла, но страницы уже сняты — молча их не выбрасываем.
 */
@Composable
private fun FatalErrorOverlay(
    message: String,
    pageCount: Int,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Камера недоступна",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Уже снято страниц: $pageCount — их можно вернуть.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onDone, enabled = pageCount > 0) { Text("Готово ($pageCount)") }
            OutlinedButton(onClick = onRetry) { Text("Повторить") }
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
    }
}
