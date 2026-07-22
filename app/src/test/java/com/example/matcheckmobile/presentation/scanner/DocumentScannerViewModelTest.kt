package com.example.matcheckmobile.presentation.scanner

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Тесты транзакции кадра — самого опасного для данных места сканера.
 *
 * Проверяем правило «страницу не теряем ни при каких обстоятельствах»: при любой
 * осечке обрезки страницей обязан стать оригинал, а мусор — исчезнуть.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentScannerViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var appScope: CoroutineScope

    /** Файлы настоящие, «декодируемость» — по маркеру в содержимом. */
    private inner class FakeFileStore : ScannerFileStore {
        var failCreate = false
        override fun createPageFile(): File {
            if (failCreate) error("no space")
            return folder.newFile("page_${System.nanoTime()}.jpg").apply { writeText(VALID) }
        }

        override fun isDecodableJpeg(file: File): Boolean =
            file.exists() && file.readText().startsWith(VALID)
    }

    private class FakeProcessor : DocumentPageProcessor {
        var succeed = true
        var writeGarbage = false
        var calls = 0
        override suspend fun cropToQuad(source: File, target: File, quad: NormalizedQuad): Boolean {
            calls++
            return when {
                writeGarbage -> { target.writeText("broken"); true }
                succeed -> { target.writeText("$VALID-cropped"); true }
                else -> { target.delete(); false }
            }
        }
    }

    private val store = FakeFileStore()
    private val processor = FakeProcessor()

    private fun quad() = NormalizedQuad(
        QuadPoint(0.1f, 0.1f), QuadPoint(0.9f, 0.1f),
        QuadPoint(0.9f, 0.9f), QuadPoint(0.1f, 0.9f),
    )

    private fun newVm(now: Long = 0L) = DocumentScannerViewModel(
        fileStore = store,
        appScope = appScope,
        handle = SavedStateHandle(),
        processor = processor,
        ioDispatcher = dispatcher,
        stabilizer = QuadStabilizer(requiredStreak = 1),
        clock = { now },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appScope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Доводит сканер до состояния «можно снимать». */
    private fun DocumentScannerViewModel.ready(): DocumentScannerViewModel = apply {
        onSessionStart()
        onCameraReady()
    }

    @Test
    fun `page without quad keeps the original untouched`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!!
        assertNull("рамки не было — снимка квада быть не должно", ticket.quadSnapshot)

        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertEquals(listOf(ticket.file.absolutePath), vm.state.value.pages)
        assertTrue(ticket.file.exists())
        assertEquals("обрезка не запускалась", 0, processor.calls)
    }

    @Test
    fun `successful crop replaces page and removes the original`() = runTest(dispatcher) {
        val vm = newVm().ready()
        vm.onQuadDetected(quad())
        val ticket = vm.beginCapture()!!
        assertNotNull("квад должен попасть в тикет при затворе", ticket.quadSnapshot)

        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        val page = vm.state.value.pages.single()
        assertTrue("страницей стал результат обрезки", page != ticket.file.absolutePath)
        assertTrue(File(page).exists())
        assertFalse("оригинал удалён", ticket.file.exists())
    }

    @Test
    fun `failed crop falls back to the original`() = runTest(dispatcher) {
        processor.succeed = false
        val vm = newVm().ready()
        vm.onQuadDetected(quad())
        val ticket = vm.beginCapture()!!

        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertEquals(listOf(ticket.file.absolutePath), vm.state.value.pages)
        assertTrue("оригинал обязан выжить", ticket.file.exists())
    }

    @Test
    fun `undecodable crop result falls back to the original`() = runTest(dispatcher) {
        // Процессор отчитался об успехе, но записал мусор — доверять нельзя.
        processor.writeGarbage = true
        val vm = newVm().ready()
        vm.onQuadDetected(quad())
        val ticket = vm.beginCapture()!!

        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertEquals(listOf(ticket.file.absolutePath), vm.state.value.pages)
        assertTrue(ticket.file.exists())
    }

    // ── debugCapture: строки диагностики HUD должны точно называть ветку ──
    // (BuildConfig.DEBUG=true в testDebugUnitTest, поле заполняется)

    @Test
    fun `debugCapture reports cropped with area on success`() = runTest(dispatcher) {
        val vm = newVm().ready()
        vm.onQuadDetected(quad())
        val ticket = vm.beginCapture()!!
        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertTrue(vm.state.value.debugCapture!!.startsWith("capture: cropped area="))
    }

    @Test
    fun `debugCapture distinguishes processor-failed from result-invalid`() = runTest(dispatcher) {
        processor.succeed = false
        val vm1 = newVm().ready()
        vm1.onQuadDetected(quad())
        vm1.onFrameSaved(vm1.beginCapture()!!)
        advanceUntilIdle()
        assertTrue(vm1.state.value.debugCapture!!.contains("обрезка не удалась"))

        processor.succeed = true
        processor.writeGarbage = true
        val vm2 = newVm().ready()
        vm2.onQuadDetected(quad())
        vm2.onFrameSaved(vm2.beginCapture()!!)
        advanceUntilIdle()
        assertTrue(vm2.state.value.debugCapture!!.contains("результат битый"))
    }

    @Test
    fun `debugCapture reports FULL when no quad snapshot`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!! // рамки не было → quadSnapshot == null
        assertNull(ticket.quadSnapshot)
        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertTrue(vm.state.value.debugCapture!!.contains("рамки не было"))
    }

    @Test
    fun `beginCapture marks processing so stale result is not shown`() = runTest(dispatcher) {
        val vm = newVm().ready()
        vm.onQuadDetected(quad())
        vm.beginCapture()

        assertEquals("capture: обработка…", vm.state.value.debugCapture)
    }

    @Test
    fun `broken original is dropped and reported`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!!
        ticket.file.writeText("broken")

        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertTrue(vm.state.value.pages.isEmpty())
        assertFalse(ticket.file.exists())
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun `stale ticket never becomes a page`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!!
        // Сессию перезапустили (поворот/возврат процесса) — токен протух.
        vm.onSessionStart()

        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        assertTrue(vm.state.value.pages.isEmpty())
        assertFalse("файл протухшего тикета удалён", ticket.file.exists())
    }

    @Test
    fun `cancel wipes pages and pending files`() = runTest(dispatcher) {
        val vm = newVm().ready()
        vm.onQuadDetected(quad())
        val ticket = vm.beginCapture()!!
        vm.onFrameSaved(ticket)
        advanceUntilIdle()
        val page = File(vm.state.value.pages.single())
        assertTrue(page.exists())

        vm.cancelSession()
        advanceUntilIdle()

        assertTrue(vm.state.value.pages.isEmpty())
        assertFalse("файлы сессии удалены", page.exists())
    }

    @Test
    fun `finish transfers ownership so files survive`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!!
        vm.onFrameSaved(ticket)
        advanceUntilIdle()

        val pages = vm.takePagesForResult()
        vm.disposeSessionFiles()
        advanceUntilIdle()

        assertEquals(1, pages.size)
        assertTrue("после передачи владения файлы удалять нельзя", File(pages.single()).exists())
    }

    @Test
    fun `session start clears orphan from a dead process`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!!

        // Процесс «умер» между съёмкой и валидацией: новый старт обязан прибрать.
        vm.onSessionStart()
        advanceUntilIdle()

        assertFalse(ticket.file.exists())
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun `stale quad is not used for cropping`() = runTest(dispatcher) {
        val vm = DocumentScannerViewModel(
            fileStore = store,
            appScope = appScope,
            handle = SavedStateHandle(),
            processor = processor,
            ioDispatcher = dispatcher,
            stabilizer = QuadStabilizer(requiredStreak = 1),
            // Между кадром и затвором «прошло» больше TTL.
            clock = object : () -> Long {
                private var calls = 0
                override fun invoke(): Long = if (calls++ == 0) 0L else QUAD_TTL_MS + 1
            },
        ).ready()
        vm.onQuadDetected(quad())

        val ticket = vm.beginCapture()!!

        assertNull("протухший квад в тикет не попадает", ticket.quadSnapshot)
    }

    @Test
    fun `capture is blocked until camera is ready`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onSessionStart()

        assertNull(vm.beginCapture())
    }

    @Test
    fun `remove page deletes its file`() = runTest(dispatcher) {
        val vm = newVm().ready()
        val ticket = vm.beginCapture()!!
        vm.onFrameSaved(ticket)
        advanceUntilIdle()
        val page = File(vm.state.value.pages.single())

        vm.removePage(page.absolutePath)
        advanceUntilIdle()

        assertTrue(vm.state.value.pages.isEmpty())
        assertFalse(page.exists())
    }

    private companion object {
        const val VALID = "JPEG"
    }
}
