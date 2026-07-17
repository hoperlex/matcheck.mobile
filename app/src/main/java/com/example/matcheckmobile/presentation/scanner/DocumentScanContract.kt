package com.example.matcheckmobile.presentation.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Результат съёмки документов. Явный тип вместо голого списка путей: вызывающему
 * экрану нужно различать «оператор отменил» (молчим) и «камера не открылась»
 * (показываем snackbar) — раньше сбой сканера был неотличим от отмены и просто
 * терялся.
 */
sealed interface DocumentScanResult {
    /** Страницы сняты; владение файлами переходит вызывающему экрану. */
    data class Success(val paths: List<String>) : DocumentScanResult

    /** Оператор вышел сам. Файлы сессии уже удалены сканером. */
    data object Cancelled : DocumentScanResult

    /** Камера не открылась/сломалась. [message] уходит в snackbar. */
    data class Failure(val message: String) : DocumentScanResult
}

/**
 * Запуск [DocumentScannerActivity] и разбор её результата.
 *
 * Ключи extras объявлены здесь и используются обеими сторонами (Activity кладёт,
 * контракт читает) — так рассинхрон ключей невозможен по построению.
 */
class DocumentScanContract : ActivityResultContract<Unit, DocumentScanResult>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, DocumentScannerActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): DocumentScanResult =
        decodeDocumentScanResult(
            resultCode = resultCode,
            paths = intent?.getStringArrayListExtra(EXTRA_PAGES),
            error = intent?.getStringExtra(EXTRA_ERROR),
        )

    companion object {
        const val EXTRA_PAGES = "com.example.matcheckmobile.scanner.PAGES"
        const val EXTRA_ERROR = "com.example.matcheckmobile.scanner.ERROR"

        /** Свой код результата: RESULT_OK/RESULT_CANCELED не различают сбой и отмену. */
        const val RESULT_FAILED = Activity.RESULT_FIRST_USER + 1

        const val DEFAULT_FAILURE_MESSAGE = "Не удалось открыть камеру"
    }
}

/**
 * Чистая часть разбора результата — вынесена из [DocumentScanContract.parseResult],
 * чтобы её можно было накрыть JVM-тестами без Android-рантайма (сам `Intent` на
 * JVM недоступен).
 */
internal fun decodeDocumentScanResult(
    resultCode: Int,
    paths: List<String>?,
    error: String?,
): DocumentScanResult = when (resultCode) {
    Activity.RESULT_OK -> {
        val pages = paths.orEmpty()
        // «Готово» заблокировано при пустом списке, поэтому пустой RESULT_OK —
        // аномалия. Трактуем как отмену: Success(emptyList()) заставил бы экран
        // отчитаться об успехе, не добавив ни одной страницы.
        if (pages.isEmpty()) DocumentScanResult.Cancelled
        else DocumentScanResult.Success(pages.toList())
    }

    DocumentScanContract.RESULT_FAILED -> DocumentScanResult.Failure(
        error?.takeIf { it.isNotBlank() } ?: DocumentScanContract.DEFAULT_FAILURE_MESSAGE,
    )

    // Сюда попадает и RESULT_CANCELED, и любой неожиданный код: отмена никогда
    // не должна превращаться в ошибку и дёргать snackbar.
    else -> DocumentScanResult.Cancelled
}
