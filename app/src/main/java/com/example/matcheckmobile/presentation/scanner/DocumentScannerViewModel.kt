package com.example.matcheckmobile.presentation.scanner

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ScannerUiState(
    val pages: List<String> = emptyList(),
    val cameraReady: Boolean = false,
    val captureInProgress: Boolean = false,
    /** Рамка документа для отрисовки поверх превью; `null` — документ не найден. */
    val documentQuad: NormalizedQuad? = null,
    /** Камера не поднялась. Если [pages] не пуст — не закрываем экран, а даём выбор. */
    val fatalError: String? = null,
    val message: String? = null,
    /** Строка диагностического HUD (только debug); в release не заполняется. */
    val debugStatus: String? = null,
) {
    /** Затвор заблокирован и во время кадра, и пока камера не в ready. */
    val canCapture: Boolean
        get() = cameraReady &&
            !captureInProgress &&
            fatalError == null &&
            pages.size < DocumentScannerViewModel.MAX_PAGES

    /** «Готово» недоступно во время съёмки/валидации и при пустом списке. */
    val canFinish: Boolean get() = pages.isNotEmpty() && !captureInProgress
}

/**
 * Состояние сессии съёмки документов.
 *
 * Здесь намеренно нет типов CameraX и OpenCV: Activity владеет камерой, а
 * обрезкой занимается [DocumentPageProcessor] за интерфейсом. Благодаря этому
 * транзакция кадра и правила владения файлами проверяются JVM-тестами.
 *
 * Главная забота — не оставить сироту и не потерять уже снятое.
 */
class DocumentScannerViewModel(
    private val fileStore: ScannerFileStore,
    private val appScope: CoroutineScope,
    private val handle: SavedStateHandle,
    private val processor: DocumentPageProcessor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stabilizer: QuadStabilizer = QuadStabilizer(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    private val _state = MutableStateFlow(
        ScannerUiState(pages = handle.get<ArrayList<String>>(KEY_PAGES)?.toList().orEmpty()),
    )
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    /** После передачи владения вызывающему экрану файлы чистить нельзя. */
    private var ownershipTransferred = false

    /**
     * Токен текущего кадра. Лежит в SavedStateHandle, чтобы поздний callback от
     * уже закрытой сессии не смог дописать страницу в новый список.
     */
    private var currentToken: String?
        get() = handle[KEY_TOKEN]
        set(value) {
            handle[KEY_TOKEN] = value
        }

    /** Путь снятого оригинала, ещё не ставшего страницей. */
    private var pendingPath: String?
        get() = handle[KEY_PENDING]
        set(value) {
            handle[KEY_PENDING] = value
        }

    /** Путь результата обрезки. Пишется ДО обработки, иначе смерть процесса оставит сироту. */
    private var pendingWarpPath: String?
        get() = handle[KEY_PENDING_WARP]
        set(value) {
            handle[KEY_PENDING_WARP] = value
        }

    /**
     * Тикет кадра. Квад фиксируется **в момент нажатия**: за время shutter lag
     * анализатор успеет увидеть другую страницу, руку или стол, и обрезка по
     * «свежему» кваду из будущего срезала бы половину УПД.
     */
    data class CaptureTicket(
        val file: File,
        val token: String,
        val quadSnapshot: NormalizedQuad?,
    )

    /**
     * Старт сессии — на каждом создании Activity, включая пересоздание после
     * поворота и восстановление процесса.
     *
     * Незавершённый кадр считаем неподтверждённым: файлы есть, но валидацию они
     * не прошли и списку не принадлежат.
     */
    fun onSessionStart() {
        val orphans = listOfNotNull(pendingPath, pendingWarpPath)
        currentToken = null
        pendingPath = null
        pendingWarpPath = null
        stabilizer.reset()
        _state.update {
            it.copy(captureInProgress = false, cameraReady = false, documentQuad = null)
        }
        if (orphans.isNotEmpty()) {
            deleteAll(orphans)
            _state.update { it.copy(message = "Кадр не сохранился — снимите страницу заново") }
        }
    }

    fun onCameraReady() = _state.update { it.copy(cameraReady = true, fatalError = null) }

    fun onFatalError(message: String) = _state.update {
        it.copy(cameraReady = false, captureInProgress = false, documentQuad = null, fatalError = message)
    }

    /** «Повторить» на экране фатальной ошибки — Activity перебиндит камеру. */
    fun retryAfterFatal() = _state.update { it.copy(fatalError = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Результат очередного кадра анализа. Вызывать только с main thread и только
     * после проверки generation — иначе рамка от уже отвязанного анализатора
     * всплывёт поверх новой сессии.
     */
    fun onQuadDetected(quad: NormalizedQuad?) {
        val stable = stabilizer.onFrame(quad, clock())
        _state.update { it.copy(documentQuad = stable) }
    }

    /** Сброс рамки: поворот, fallback-биндинг, остановка анализатора. */
    fun clearQuad() {
        stabilizer.reset()
        _state.update { it.copy(documentQuad = null) }
    }

    /** Диагностический HUD (только debug). Вызывать с main thread. */
    fun setDebugStatus(status: String) = _state.update { it.copy(debugStatus = status) }

    /** Опубликована ли рамка — для строки HUD (`shown`). */
    fun isQuadShown(): Boolean = _state.value.documentQuad != null

    /**
     * Открывает транзакцию кадра: фиксирует pendingPath, токен и **снимок квада**
     * ДО takePicture.
     */
    fun beginCapture(): CaptureTicket? {
        if (!_state.value.canCapture) return null
        val file = try {
            fileStore.createPageFile()
        } catch (t: Throwable) {
            _state.update { it.copy(message = t.message ?: "Не удалось создать файл снимка") }
            return null
        }
        val token = UUID.randomUUID().toString()
        pendingPath = file.absolutePath
        currentToken = token
        _state.update { it.copy(captureInProgress = true) }
        return CaptureTicket(file, token, stabilizer.snapshotIfFresh(clock()))
    }

    /**
     * Кадр записан. Порядок строгий, потому что терять страницу нельзя:
     * оригинал → проверка → warp в отдельный файл → проверка → подмена.
     * Любая осечка оставляет страницей оригинал.
     */
    fun onFrameSaved(ticket: CaptureTicket) {
        viewModelScope.launch {
            if (isStale(ticket)) return@launch deleteAll(listOf(ticket.file.absolutePath))

            val originalOk = withContext(ioDispatcher) { fileStore.isDecodableJpeg(ticket.file) }
            if (isStale(ticket)) return@launch deleteAll(listOf(ticket.file.absolutePath))

            if (!originalOk) {
                deleteAll(listOf(ticket.file.absolutePath))
                clearPending()
                _state.update {
                    it.copy(captureInProgress = false, message = "Снимок повреждён — снимите заново")
                }
                return@launch
            }

            val page = ticket.quadSnapshot?.let { quad -> cropOrFallback(ticket, quad) }
                ?: ticket.file.absolutePath

            // Сессию могли закрыть, пока шла обрезка: тогда оба файла — мусор.
            if (isStale(ticket)) {
                return@launch deleteAll(listOfNotNull(ticket.file.absolutePath, pendingWarpPath, page))
            }

            setPages(_state.value.pages + page)
            clearPending()
            _state.update { it.copy(captureInProgress = false) }
        }
    }

    /**
     * Обрезает по рамке. Возвращает путь страницы: результат обрезки при успехе,
     * иначе — оригинал. Необрезанный документ хуже красивого, но потерянный —
     * хуже обоих.
     */
    private suspend fun cropOrFallback(ticket: CaptureTicket, quad: NormalizedQuad): String {
        val target = try {
            fileStore.createPageFile()
        } catch (t: Throwable) {
            return ticket.file.absolutePath
        }
        // Путь фиксируем ДО обработки: иначе смерть процесса во время warp
        // оставит файл, о котором никто не знает.
        pendingWarpPath = target.absolutePath

        val ok = processor.cropToQuad(ticket.file, target, quad) &&
            withContext(ioDispatcher) { fileStore.isDecodableJpeg(target) }

        return if (ok) {
            deleteAll(listOf(ticket.file.absolutePath))
            pendingWarpPath = null
            target.absolutePath
        } else {
            deleteAll(listOf(target.absolutePath))
            pendingWarpPath = null
            ticket.file.absolutePath
        }
    }

    fun onFrameFailed(ticket: CaptureTicket, message: String) {
        deleteAll(listOf(ticket.file.absolutePath))
        if (isStale(ticket)) return
        clearPending()
        _state.update { it.copy(captureInProgress = false, message = message) }
    }

    fun removePage(path: String) {
        setPages(_state.value.pages - path)
        deleteAll(listOf(path))
    }

    /**
     * Передаёт владение файлами вызывающему экрану. Вызывать строго ДО
     * setResult/finish — после этого ни onCleared, ни отмена их не удалят.
     */
    fun takePagesForResult(): List<String> {
        ownershipTransferred = true
        return _state.value.pages
    }

    /** Отмена/Back: инвалидируем токен и чистим всё, что сняли в этой сессии. */
    fun cancelSession() {
        currentToken = null
        val doomed = _state.value.pages + listOfNotNull(pendingPath, pendingWarpPath)
        pendingPath = null
        pendingWarpPath = null
        stabilizer.reset()
        setPages(emptyList())
        deleteAll(doomed)
    }

    override fun onCleared() = disposeSessionFiles()

    /**
     * Уборка при закрытии экрана. Вынесена из `onCleared` (он `protected`), чтобы
     * правило «после передачи владения файлы не трогаем» проверялось тестом, а не
     * держалось на честном слове.
     */
    internal fun disposeSessionFiles() {
        if (!ownershipTransferred) {
            deleteAll(_state.value.pages + listOfNotNull(pendingPath, pendingWarpPath))
        }
    }

    private fun isStale(ticket: CaptureTicket) = ticket.token != currentToken

    private fun clearPending() {
        pendingPath = null
        pendingWarpPath = null
        currentToken = null
    }

    private fun setPages(pages: List<String>) {
        val copy = pages.toList()
        _state.update { it.copy(pages = copy) }
        handle[KEY_PAGES] = ArrayList(copy)
    }

    /** Чистим через appScope: viewModelScope уже отменён к моменту закрытия Activity. */
    private fun deleteAll(paths: List<String>) {
        if (paths.isEmpty()) return
        val snapshot = paths.distinct()
        appScope.launch { snapshot.forEach { runCatching { File(it).delete() } } }
    }

    companion object {
        const val MAX_PAGES = 20
        private const val KEY_PAGES = "scanner_pages"
        private const val KEY_PENDING = "scanner_pending_path"
        private const val KEY_PENDING_WARP = "scanner_pending_warp_path"
        private const val KEY_TOKEN = "scanner_capture_token"
    }
}
