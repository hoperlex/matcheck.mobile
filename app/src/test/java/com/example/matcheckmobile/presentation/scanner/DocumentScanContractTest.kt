package com.example.matcheckmobile.presentation.scanner

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты разбора результата сканера. Проверяют то, на чём легко потерять данные:
 * порядок страниц, отличие отмены от сбоя и сохранность текста ошибки.
 */
class DocumentScanContractTest {

    @Test
    fun `success preserves path order`() {
        val paths = (1..20).map { "/data/operation_photos/doc_$it.jpg" }

        val result = decodeDocumentScanResult(Activity.RESULT_OK, paths, null)

        assertEquals(DocumentScanResult.Success(paths), result)
        assertEquals(paths, (result as DocumentScanResult.Success).paths)
    }

    @Test
    fun `success with twenty pages keeps all of them`() {
        val paths = (1..20).map { "/data/operation_photos/doc_$it.jpg" }

        val result = decodeDocumentScanResult(Activity.RESULT_OK, paths, null)

        assertEquals(20, (result as DocumentScanResult.Success).paths.size)
    }

    @Test
    fun `failure preserves message`() {
        val result = decodeDocumentScanResult(
            DocumentScanContract.RESULT_FAILED,
            null,
            "Камера занята другим приложением",
        )

        assertEquals(DocumentScanResult.Failure("Камера занята другим приложением"), result)
    }

    @Test
    fun `failure without message falls back to default`() {
        val blank = decodeDocumentScanResult(DocumentScanContract.RESULT_FAILED, null, "   ")
        val missing = decodeDocumentScanResult(DocumentScanContract.RESULT_FAILED, null, null)

        val expected = DocumentScanResult.Failure(DocumentScanContract.DEFAULT_FAILURE_MESSAGE)
        assertEquals(expected, blank)
        assertEquals(expected, missing)
    }

    @Test
    fun `cancelled does not become failure`() {
        val result = decodeDocumentScanResult(Activity.RESULT_CANCELED, null, null)

        assertEquals(DocumentScanResult.Cancelled, result)
        assertTrue(result !is DocumentScanResult.Failure)
    }

    @Test
    fun `cancelled stays cancelled even if an error extra leaked in`() {
        val result = decodeDocumentScanResult(Activity.RESULT_CANCELED, null, "boom")

        assertEquals(DocumentScanResult.Cancelled, result)
    }

    @Test
    fun `empty ok is treated as cancelled not as empty success`() {
        val result = decodeDocumentScanResult(Activity.RESULT_OK, emptyList(), null)

        assertEquals(DocumentScanResult.Cancelled, result)
    }

    @Test
    fun `unknown result code is treated as cancelled`() {
        val result = decodeDocumentScanResult(Impossible_RESULT_CODE, null, null)

        assertEquals(DocumentScanResult.Cancelled, result)
    }

    private companion object {
        const val Impossible_RESULT_CODE = 0xDEAD
    }
}
