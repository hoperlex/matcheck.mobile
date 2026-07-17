package com.example.matcheckmobile.presentation.scanner

import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.media.PhotoStorage
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
    /** Камера не поднялась. Если [pages] не пуст — не закрываем экран, а даём выбор. */
    val fatalError: String? = null,
    val message: String? = null,
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
 * Здесь намеренно нет типов CameraX: Activity владеет камерой и лишь сообщает
 * сюда о событиях кадра. Это оставляет транзакцию кадра и правила владения
 * файлами проверяемыми обычными JVM-тестами.
 *
 * Главная забота — не оставить сироту и не потерять уже снятое. Между
 * `createTempFile` и `pages += path` файл уже существует, но списку ещё не
 * принадлежит; поворот, Back или смерть процесса в этом окне раньше дали бы
 * либо мусор в operation_photos, либо дубль.
 */
class DocumentScannerViewModel(
    private val photoStorage: PhotoStorage,
    private val appScope: CoroutineScope,
    private val handle: SavedStateHandle,
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

    private var pendingPath: String?
        get() = handle[KEY_PENDING]
        set(value) {
            handle[KEY_PENDING] = value
        }

    data class CaptureTicket(val file: File, val token: String)

    /**
     * Старт сессии — вызывается на каждом создании Activity, включая пересоздание
     * после поворота и восстановление процесса.
     *
     * Незавершённый кадр считаем неподтверждённым: файл есть, но валидацию он не
     * прошёл и списку не принадлежит. Удаляем и просим переснять — это надёжнее,
     * чем пытаться «донести» кадр через пересоздание.
     */
    fun onSessionStart() {
        val orphan = pendingPath
        currentToken = null
        pendingPath = null
        _state.update { it.copy(captureInProgress = false, cameraReady = false) }
        if (orphan != null) {
            appScope.launch { runCatching { File(orphan).delete() } }
            _state.update { it.copy(message = "Кадр не сохранился — снимите страницу заново") }
        }
    }

    fun onCameraReady() = _state.update { it.copy(cameraReady = true, fatalError = null) }

    fun onFatalError(message: String) =
        _state.update { it.copy(cameraReady = false, captureInProgress = false, fatalError = message) }

    /** «Повторить» на экране фатальной ошибки — Activity перебиндит камеру. */
    fun retryAfterFatal() = _state.update { it.copy(fatalError = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Открывает транзакцию кадра: фиксирует pendingPath и токен ДО takePicture,
     * чтобы файл был отслеживаем даже если процесс умрёт сразу после съёмки.
     */
    fun beginCapture(): CaptureTicket? {
        if (!_state.value.canCapture) return null
        val file = try {
            photoStorage.createTempFile(DOC_PREFIX)
        } catch (t: Throwable) {
            _state.update { it.copy(message = t.message ?: "Не удалось создать файл снимка") }
            return null
        }
        val token = UUID.randomUUID().toString()
        pendingPath = file.absolutePath
        currentToken = token
        _state.update { it.copy(captureInProgress = true) }
        return CaptureTicket(file, token)
    }

    /**
     * Кадр записан. Транзакция закрывается только после успешной валидации JPEG:
     * `length > 0` ничего не гарантирует, а недекодируемый файл всплыл бы только
     * на submit — то есть в момент, когда терять данные дороже всего.
     */
    fun onFrameSaved(ticket: CaptureTicket) {
        viewModelScope.launch {
            if (isStale(ticket)) return@launch deleteOrphan(ticket.file)

            val decodable = withContext(Dispatchers.IO) { isDecodableJpeg(ticket.file) }

            // Пока валидировали, сессию могли закрыть или перезапустить.
            if (isStale(ticket)) return@launch deleteOrphan(ticket.file)

            if (!decodable) {
                deleteOrphan(ticket.file)
                clearPending()
                _state.update {
                    it.copy(captureInProgress = false, message = "Снимок повреждён — снимите заново")
                }
                return@launch
            }

            setPages(_state.value.pages + ticket.file.absolutePath)
            clearPending()
            _state.update { it.copy(captureInProgress = false) }
        }
    }

    fun onFrameFailed(ticket: CaptureTicket, message: String) {
        deleteOrphan(ticket.file)
        if (isStale(ticket)) return
        clearPending()
        _state.update { it.copy(captureInProgress = false, message = message) }
    }

    fun removePage(path: String) {
        setPages(_state.value.pages - path)
        appScope.launch { runCatching { File(path).delete() } }
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
        val doomed = _state.value.pages + listOfNotNull(pendingPath)
        pendingPath = null
        setPages(emptyList())
        deleteAll(doomed)
    }

    override fun onCleared() {
        if (!ownershipTransferred) {
            deleteAll(_state.value.pages + listOfNotNull(pendingPath))
        }
    }

    private fun isStale(ticket: CaptureTicket) = ticket.token != currentToken

    private fun clearPending() {
        pendingPath = null
        currentToken = null
    }

    private fun setPages(pages: List<String>) {
        val copy = pages.toList()
        _state.update { it.copy(pages = copy) }
        handle[KEY_PAGES] = ArrayList(copy)
    }

    private fun deleteOrphan(file: File) {
        appScope.launch { runCatching { file.delete() } }
    }

    /** Чистим через appScope: viewModelScope уже отменён к моменту закрытия Activity. */
    private fun deleteAll(paths: List<String>) {
        if (paths.isEmpty()) return
        val snapshot = paths.toList()
        appScope.launch { snapshot.forEach { runCatching { File(it).delete() } } }
    }

    companion object {
        const val MAX_PAGES = 20
        private const val DOC_PREFIX = "doc"
        private const val KEY_PAGES = "scanner_pages"
        private const val KEY_PENDING = "scanner_pending_path"
        private const val KEY_TOKEN = "scanner_capture_token"

        /**
         * Дешёвая проверка, что downstream сможет открыть файл: только границы,
         * без аллокации пикселей.
         */
        internal fun isDecodableJpeg(file: File): Boolean {
            if (!file.exists() || file.length() == 0L) return false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
            return opts.outWidth > 0 && opts.outHeight > 0
        }
    }
}
