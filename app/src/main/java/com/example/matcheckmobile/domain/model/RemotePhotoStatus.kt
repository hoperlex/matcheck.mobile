package com.example.matcheckmobile.domain.model

/**
 * Значения поля `uploadStatus` у remote_delivery_photos / remote_shipment_photos.
 * Поле строковое (не enum) — новый статус не требует миграции Room.
 *
 * Не путать с [UploadStatus]: тот описывает legacy-вложения
 * (operation_attachments) и живёт своим pipeline'ом.
 */
object RemotePhotoStatus {
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

    /** Статусы, которые забирает upload-цикл. Карантин сюда НЕ входит. */
    val UPLOADABLE = listOf(PENDING_UPLOAD, UPLOAD_ERROR)

    /** Фото, у которого содержимое ещё не доехало на сервер. */
    fun isUnsent(status: String): Boolean = status != UPLOADED
}
