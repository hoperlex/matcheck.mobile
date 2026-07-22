package com.example.matcheckmobile.presentation.scanner

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.PhotoThumb
import com.example.matcheckmobile.ui.theme.MatcheckmobileTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "DocumentScanner"

/** Не чаще ~10 анализов в секунду: KEEP_ONLY_LATEST убирает очередь, но не расход CPU. */
private const val ANALYSIS_MIN_INTERVAL_MS = 100L

/**
 * Собственный сканер документов на CameraX с живой рамкой краёв.
 *
 * Почему отдельная Activity: MainActivity объявлена `resizeableActivity="false"`
 * и реактивно переписывает ориентацию из DataStore — камера внутри неё
 * унаследовала бы леттербокс и вошла бы в гонку за requestedOrientation.
 *
 * `configChanges` для ориентации намеренно НЕ объявлен: при повороте Activity
 * пересоздаётся и CameraX биндится заново под новую геометрию. Иначе в
 * UseCaseGroup остался бы ViewPort от старого экрана и снимок разошёлся бы
 * с превью.
 */
class DocumentScannerActivity : ComponentActivity() {

    private val vm: DocumentScannerViewModel by viewModels {
        val container = (application as MatcheckApplication).container
        viewModelFactory {
            initializer {
                DocumentScannerViewModel(
                    fileStore = PhotoStorageFileStore(container.photoStorage),
                    appScope = container.appScope,
                    handle = createSavedStateHandle(),
                    processor = OpenCvDocumentPageProcessor(),
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
        // закрывает незавершённый кадр: файлы есть, но валидацию не прошли.
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
    // Поколение сессии анализа: результат старого анализатора не должен всплыть
    // рамкой поверх новой привязки после поворота или фолбэка.
    val analysisGeneration = remember { AtomicInteger(0) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    DisposableEffect(previewView, lifecycleOwner, bindAttempt) {
        var provider: ProcessCameraProvider? = null
        val bound = mutableListOf<UseCase>()
        var analysis: ImageAnalysis? = null
        var disposed = false
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val generation = analysisGeneration.incrementAndGet()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        // Диагностика (см. HUD): кадры считаем в самом callback анализатора —
        // «привязалось» (bound) и «кадры идут» (running) это разные вещи.
        val frames = AtomicInteger(0)
        val analysisState = AtomicReference("starting")

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

                    val capture = buildImageCapture(rotation)
                    val detector = DocumentEdgeDetector()
                    // Тот же targetRotation, что у превью и захвата: CameraX считает
                    // rotationDegrees относительно target rotation конкретного
                    // use case, иначе рамка разъедется в альбоме.
                    val analyzer = ImageAnalysis.Builder()
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                    analysis = analyzer

                    var lastAnalysisAt = 0L
                    analyzer.setAnalyzer(analysisExecutor) { proxy ->
                        try {
                            if (analysisGeneration.get() != generation) return@setAnalyzer
                            // Кадр реально пришёл — считаем ВСЕ кадры (до троттлинга).
                            val f = frames.incrementAndGet()
                            analysisState.set("running")
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastAnalysisAt < ANALYSIS_MIN_INTERVAL_MS) return@setAnalyzer
                            lastAnalysisAt = now

                            val result = analyzeFrame(detector, proxy)
                            mainExecutor.execute {
                                // Второй раз сверяем поколение уже на main: пока
                                // летели сюда, сессию могли перепривязать.
                                if (analysisGeneration.get() != generation) return@execute
                                vm.onQuadDetected(result.quad)
                                if (BuildConfig.DEBUG) {
                                    vm.setDebugStatus(
                                        debugHud(analysisState.get(), f, result, vm.isQuadShown()),
                                    )
                                }
                            }
                        } finally {
                            proxy.close()
                        }
                    }

                    // ViewPort связывает превью, анализ и захват с одной областью
                    // сенсора: без него снимок и рамка не совпадут с превью.
                    val useCases = bindWithFallback(
                        cameraProvider, lifecycleOwner, viewPort, preview, capture, analyzer,
                    )
                    bound.addAll(useCases)
                    val analysisBound = analyzer in useCases
                    if (!analysisBound) {
                        // Три use case не поднялись — работаем без рамки.
                        analyzer.clearAnalyzer()
                        analysis = null
                        analysisState.set("dropped")
                        Log.i(TAG, "analysis dropped (fallback to preview+capture)")
                        mainExecutor.execute { vm.clearQuad() }
                    } else {
                        analysisState.set("bound")
                        Log.i(TAG, "analysis bound (preview+capture+analysis)")
                    }
                    // Стартовая строка HUD: если кадры не пойдут вовсе — на экране
                    // останется «analysis: bound/dropped, frames(0)», это и есть диагноз.
                    if (BuildConfig.DEBUG) {
                        vm.setDebugStatus(debugHud(analysisState.get(), 0, FrameAnalysis(null, EMPTY_STATS), false))
                    }
                    imageCapture = capture
                    vm.onCameraReady()
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera bind failed", t)
                    onFatal(t.message ?: "Не удалось запустить камеру")
                }
            }, mainExecutor)
        }

        onDispose {
            disposed = true
            imageCapture = null
            // Порядок важен: сперва глушим поток кадров, потом отвязываем и только
            // затем гасим executor — иначе CameraX отправит кадр в закрытый пул.
            analysisGeneration.incrementAndGet()
            analysis?.clearAnalyzer()
            val stillBound = bound.filter { provider?.isBound(it) == true }
            if (stillBound.isNotEmpty()) provider?.unbind(*stillBound.toTypedArray())
            mainExecutor.execute { vm.clearQuad() }
            analysisExecutor.shutdown()
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

        // Рамка документа. Canvas совпадает с превью по размеру, поэтому
        // нормализованный квад разворачивается простым умножением.
        state.documentQuad?.let { quad ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pts = quad.points.map { it.x * size.width to it.y * size.height }
                val path = Path().apply {
                    moveTo(pts[0].first, pts[0].second)
                    for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
                    close()
                }
                drawPath(path, color = Color(0xFF4CAF50), style = Stroke(width = 4.dp.toPx()))
            }
        }

        // Диагностический HUD — только в debug. Показывает, где теряется рамка
        // (см. дерево диагностики в плане). В release не рисуется.
        if (BuildConfig.DEBUG) {
            state.debugStatus?.let { status ->
                Text(
                    text = status,
                    color = Color(0xFF00E676),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

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

private fun buildImageCapture(rotation: Int): ImageCapture =
    ImageCapture.Builder()
        .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetRotation(rotation)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                // Downstream жмёт до 2048 и не апскейлит — ниже опускаться нельзя,
                // иначе УПД станет нечитаемым.
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(2048, 1536),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        .build()

/**
 * Пытается поднять три use case, при неудаче — превью с захватом.
 *
 * Связка Preview + ImageCapture + ImageAnalysis поддерживается не на каждом
 * устройстве, и терять из-за рамки саму съёмку нельзя. Фатально только если
 * не поднялась и вторая попытка.
 */
private fun bindWithFallback(
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    viewPort: androidx.camera.core.ViewPort,
    preview: Preview,
    capture: ImageCapture,
    analysis: ImageAnalysis,
): List<UseCase> {
    fun group(vararg useCases: UseCase) = UseCaseGroup.Builder()
        .setViewPort(viewPort)
        .apply { useCases.forEach { addUseCase(it) } }
        .build()

    return try {
        provider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group(preview, capture, analysis),
        )
        listOf(preview, capture, analysis)
    } catch (t: Throwable) {
        Log.w(TAG, "Bind with analysis failed, retrying without it", t)
        provider.unbind(preview, capture, analysis)
        provider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group(preview, capture),
        )
        listOf(preview, capture)
    }
}

/** Результат разбора кадра: рамка (или null) + счётчики для диагностики. */
private data class FrameAnalysis(val quad: NormalizedQuad?, val stats: DetectResult)

/**
 * Строка диагностического HUD. `valid` (квад прошёл валидацию в этом кадре) и
 * `shown` (рамка реально опубликована после стабилизатора) — разные вещи; их
 * расхождение указывает на проблему стабилизации, а не порогов.
 */
private fun debugHud(analysisState: String, frames: Int, result: FrameAnalysis, shown: Boolean): String {
    val cv = when (OpenCvBootstrap.state) {
        OpenCvBootstrap.State.PENDING -> if (analysisState == "dropped") "n/a" else "pending"
        OpenCvBootstrap.State.OK -> "ok"
        OpenCvBootstrap.State.FAIL -> "FAIL"
    }
    val detect = if (result.stats.error) "FAIL" else "ok"
    val valid = if (result.quad != null) "yes" else "no"
    val s = result.stats
    return "cv: $cv · analysis: $analysisState frames($frames) · detect: $detect\n" +
        "raw: ${s.raw} · p4: ${s.exact4} · p5-6: ${s.near4} · valid: $valid · shown: ${if (shown) "yes" else "no"}"
}

private val EMPTY_STATS = DetectResult(emptyList(), raw = 0, exact4 = 0, near4 = 0, error = false)

/**
 * Достаёт из кадра рамку документа и статистику детекта.
 *
 * Работаем только по `cropRect`: полный буфер в портрете содержит невидимые
 * оператору полосы, и найденный там контур стал бы рамкой-призраком.
 */
private fun analyzeFrame(detector: DocumentEdgeDetector, proxy: ImageProxy): FrameAnalysis {
    val plane = proxy.planes.firstOrNull() ?: return FrameAnalysis(null, EMPTY_STATS)
    val buffer = plane.buffer.apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val crop = proxy.cropRect
    val luma = cropLuma(
        plane = bytes,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropWidth = crop.width(),
        cropHeight = crop.height(),
    ) ?: return FrameAnalysis(null, EMPTY_STATS)

    val stats = detector.detectWithStats(luma, crop.width(), crop.height())
    val candidates = stats.candidates.mapNotNull {
        buildNormalizedQuad(it, crop.width(), crop.height(), proxy.imageInfo.rotationDegrees)
    }
    return FrameAnalysis(selectLargestQuad(candidates), stats)
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
