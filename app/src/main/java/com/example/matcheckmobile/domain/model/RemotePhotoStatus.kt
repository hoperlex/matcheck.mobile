package com.example.matcheckmobile.domain.model

/**
 * Значения поля `uploadStatus` у remote_delivery_photos / remote_shipment_photos.
 * Поле строковое (не enum) — новый статус не требует миграции Room.
 *
 * Не путать с [UploadStatus]: тот описывает legacy-вложения
 * (operation_attachments) и живёт своим pipeline'ом.
 */
object RemotePhotoStatus {
    /**
     * Кадр снят и durable-строка создана в одной транзакции с приёмкой, но
     * main+thumb ещё не собраны: в строке есть только [sourcePath] на исходник
     * в operation_photos. Тяжёлую подготовку делает PhotoPrepareWorker —
     * локально, без сети.
     *
     * Раньше подготовка шла прямо в finalize() формы, и её падение (нет места,
     * OOM) оставляло приёмку в очереди совсем без фото — см. [PhotoIntent].
     */
    const val PENDING_PREPARE = "PENDING_PREPARE"

    /**
     * Строку взял в работу PhotoPrepareWorker; `preparingSince` — момент
     * захвата. Смерть процесса здесь не терминальна: просроченный lease
     * перехватывает следующий запуск воркера.
     */
    const val PREPARING = "PREPARING"

    /** Подготовка не удалась (нет места, битый файл, OOM). Ретраится вечно. */
    const val PREPARE_ERROR = "PREPARE_ERROR"

    const val PENDING_UPLOAD = "PENDING_UPLOAD"
    const val UPLOADING = "UPLOADING"
    const val UPLOADED = "UPLOADED"
    const val UPLOAD_ERROR = "UPLOAD_ERROR"

    /**
     * Терминальный статус: фото относится к записи ЧУЖОГО объекта, сервер
     * отвечает 403 `foreign_site` и будет отвечать так всегда.
     *
     * Почему отдельный статус, а не UPLOAD_ERROR: `PhotoUploadProcessor.processAll`
     * выбирает PENDING_UPLOAD **и** UPLOAD_ERROR, то есть карантин через
     * UPLOAD_ERROR дал бы бесконечный цикл 403 на каждом синке. Файл и строка
     * при этом сохраняются: удалить снимок можно только руками из «Очереди
     * синхронизации» — требование «без потерь данных».
     */
    const val QUARANTINED_FOREIGN_SITE = "QUARANTINED_FOREIGN_SITE"

    /**
     * Статусы, которые забирает upload-цикл. Карантин сюда НЕ входит.
     *
     * PENDING_PREPARE / PREPARE_ERROR тоже НЕ входят: у них ещё нет
     * localBlobPath, и upload-цикл принял бы их за «blob missing».
     */
    val UPLOADABLE = listOf(PENDING_UPLOAD, UPLOAD_ERROR)

    /** Статусы, которые забирает prepare-цикл (локальный, без сети). */
    val PREPARABLE = listOf(PENDING_PREPARE, PREPARE_ERROR)

    /**
     * Ещё не отправленное содержимое — для барьеров выхода/смены аккаунта,
     * счётчиков на главной и экрана очереди. Включает и стадию подготовки:
     * иначе кадр, который ещё не собран, был бы невидим для всех проверок.
     */
    val UNSENT = listOf(
        PENDING_PREPARE,
        PREPARING,
        PREPARE_ERROR,
        PENDING_UPLOAD,
        UPLOADING,
        UPLOAD_ERROR,
    )

    /**
     * Строки, у которых localBlobPath пуст ПО ОПРЕДЕЛЕНИЮ, а не потому, что
     * blob потерялся. Чистилка «сломанных» строк обязана их пропускать.
     */
    val AWAITING_PREPARE = listOf(PENDING_PREPARE, PREPARING, PREPARE_ERROR)

    /** Фото, у которого содержимое ещё не доехало на сервер. */
    fun isUnsent(status: String): Boolean = status != UPLOADED
}
