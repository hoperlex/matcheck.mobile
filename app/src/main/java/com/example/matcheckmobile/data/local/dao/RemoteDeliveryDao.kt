package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteDeliveryDao {

    @Upsert
    suspend fun upsert(entity: RemoteDeliveryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RemoteDeliveryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceItems(items: List<RemoteDeliveryItemEntity>)

    @Query("DELETE FROM remote_delivery_items WHERE deliveryId = :deliveryId")
    suspend fun deleteItemsByDelivery(deliveryId: String)

    @Query("DELETE FROM remote_delivery_photos WHERE deliveryId = :deliveryId")
    suspend fun deletePhotosByDelivery(deliveryId: String)

    @Query("DELETE FROM remote_delivery_photos WHERE uploadStatus = :status AND lastUploadError = :error")
    suspend fun deletePhotosByStatusAndError(status: String, error: String): Int

    @androidx.room.Upsert
    suspend fun upsertPhoto(photo: RemoteDeliveryPhotoEntity)

    /**
     * Вставка photo intents при финализации. IGNORE — принципиально:
     * повторная финализация (инспектор нажал «Завершить» второй раз, процесс
     * умер до удаления черновика) не должна откатывать строку, которая уже
     * прошла подготовку или заливку, обратно в PENDING_PREPARE и терять
     * localBlobPath/contentHash.
     *
     * Конфликт ловится и по PK (детерминированный id), и по уникальному
     * индексу (deliveryId, sourcePath).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhotoIntents(photos: List<RemoteDeliveryPhotoEntity>)

    /**
     * CAS-захват кадра воркером подготовки. Возвращает число изменённых строк:
     * 0 означает, что строку уже забрал другой воркер — её надо пропустить.
     */
    @Query(
        """
        UPDATE remote_delivery_photos
        SET uploadStatus = 'PREPARING', preparingSince = :now
        WHERE id = :id AND uploadStatus IN ('PENDING_PREPARE', 'PREPARE_ERROR')
        """,
    )
    suspend fun claimPhotoForPrepare(id: String, now: Long): Int

    /**
     * Возврат просроченных lease: процесс убили в момент подготовки, статус
     * остался PREPARING. Без этого кадр завис бы навсегда — ровно та потеря,
     * ради которой вводился PENDING_PREPARE.
     */
    @Query(
        """
        UPDATE remote_delivery_photos
        SET uploadStatus = 'PENDING_PREPARE', preparingSince = NULL
        WHERE uploadStatus = 'PREPARING'
          AND (preparingSince IS NULL OR preparingSince < :expiredBefore)
        """,
    )
    suspend fun releaseExpiredPreparing(expiredBefore: Long): Int

    /**
     * Сколько строк всё ещё ждут подготовки из этого исходника. Исходник
     * удаляем только когда счётчик обнулился: один кадр может быть приложен
     * и к приёмке, и к отгрузке.
     */
    @Query(
        """
        SELECT COUNT(*) FROM remote_delivery_photos
        WHERE sourcePath = :sourcePath
          AND uploadStatus IN ('PENDING_PREPARE', 'PREPARING', 'PREPARE_ERROR')
        """,
    )
    suspend fun countAwaitingPrepareForSource(sourcePath: String): Int

    @Query("SELECT * FROM remote_delivery_photos WHERE uploadStatus IN (:statuses)")
    suspend fun findPhotosByStatus(statuses: List<String>): List<RemoteDeliveryPhotoEntity>

    // Recovery «зависших» загрузок: если процесс убили во время заливки фото
    // (свернули приложение / Android прибил фоновую задачу), статус остаётся
    // UPLOADING, а findPhotosByStatus(PENDING_UPLOAD, UPLOAD_ERROR) такие НЕ
    // перезабирает → фото навсегда зависает orphan'ом на сервере (uploaded_at=
    // null), и cleanup-job удаляет его через час. Сбрасываем UPLOADING обратно
    // в PENDING_UPLOAD в начале каждого processAll — на этот момент активной
    // заливки нет, значит любое UPLOADING = недогруженный остаток для повтора.
    @Query("UPDATE remote_delivery_photos SET uploadStatus = 'PENDING_UPLOAD' WHERE uploadStatus = 'UPLOADING'")
    suspend fun resetStuckUploadingPhotos(): Int

    @Query("SELECT * FROM remote_delivery_photos WHERE uploadStatus IN (:statuses) ORDER BY takenAt DESC")
    fun observePhotosByStatus(statuses: List<String>): Flow<List<RemoteDeliveryPhotoEntity>>

    @Query("SELECT COUNT(*) FROM remote_delivery_photos WHERE uploadStatus IN (:statuses)")
    fun observePhotoCountByStatus(statuses: List<String>): Flow<Int>

    @Query("SELECT * FROM remote_delivery_photos WHERE id = :id")
    suspend fun findPhotoById(id: String): RemoteDeliveryPhotoEntity?

    @Query("DELETE FROM remote_delivery_photos WHERE id = :id")
    suspend fun deletePhotoById(id: String)


    /**
     * Наблюдение за фото одной операции. Формы 2 Этапа и архива обязаны быть
     * реактивными: пока экран открыт, PhotoPrepareWorker успевает удалить
     * sourcePath, сделать thumb и сменить клиентский id на серверный. Со
     * снимком, снятым один раз при загрузке формы, UI остался бы с путями,
     * которых на диске уже нет, и показал бы «фото недоступно».
     */
    @Query("SELECT * FROM remote_delivery_photos WHERE deliveryId = :deliveryId ORDER BY takenAt")
    fun observePhotosByDelivery(deliveryId: String): Flow<List<RemoteDeliveryPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhotoIfAbsent(photo: RemoteDeliveryPhotoEntity)

    /**
     * Обновляет ТОЛЬКО серверные колонки. Локальные — localBlobPath,
     * localThumbPath, sourcePath, idempotencyKey, contentType, uploadStatus,
     * lastUploadError, preparingSince — здесь не перечислены и потому
     * сохраняются по построению.
     *
     * COALESCE, а не присваивание: серверный DTO может не знать того, что
     * знаем мы (contentHash считается локально при подготовке). Пустое
     * серверное поле не должно затирать заполненное локальное — только
     * дополнять.
     */
    @Query(
        """
        UPDATE remote_delivery_photos
        SET s3Key = COALESCE(:s3Key, s3Key),
            thumbS3Key = COALESCE(:thumbS3Key, thumbS3Key),
            contentHash = COALESCE(:contentHash, contentHash),
            uploadedAt = COALESCE(:uploadedAt, uploadedAt),
            takenAt = :takenAt,
            kind = :kind,
            stage = :stage
        WHERE id = :id
        """,
    )
    suspend fun updateServerPhotoColumns(
        id: String,
        s3Key: String?,
        thumbS3Key: String?,
        contentHash: String?,
        uploadedAt: String?,
        takenAt: String,
        kind: String,
        stage: String,
    )

    @Query("UPDATE remote_delivery_photos SET uploadStatus = 'UPLOADED', lastUploadError = NULL WHERE id = :id")
    suspend fun markPhotoUploaded(id: String)

    /**
     * Мердж строки, приехавшей с сервера, поверх локальной.
     *
     * Нельзя писать серверный DTO целиком: RemoteMappers.toEntity ставит
     * localBlobPath/localThumbPath = null, и обычный upsert стирал бы указатели
     * на файлы, которые лежат на диске. Именно из-за этого открытие 2 Этапа
     * само загоняло показ фото в S3.
     *
     * Статус с сервера НЕ применяем: если локально идёт работа (PENDING_PREPARE
     * с живым sourcePath), сервер о ней не знает, и понижение статуса отправило
     * бы кадр в UPLOAD_ERROR «blob missing» с последующим удалением строки.
     * Исключение — heal: локально отправлять уже нечего, а сервер подтверждает
     * загрузку.
     */
    @Transaction
    suspend fun upsertServerPhoto(photo: RemoteDeliveryPhotoEntity) {
        val existing = findPhotoById(photo.id)
        if (existing == null) {
            // uploadedAt = null у серверной строки означает presign без confirm:
            // объекта в S3 нет, локального blob'а тоже. Заводить такую строку —
            // значит создать uploadable-запись без файла, которая гарантированно
            // уедет в UPLOAD_ERROR «blob missing» и будет вычищена.
            if (photo.uploadedAt == null) return
            insertPhotoIfAbsent(photo)
            return
        }
        updateServerPhotoColumns(
            id = photo.id,
            s3Key = photo.s3Key,
            thumbS3Key = photo.thumbS3Key,
            contentHash = photo.contentHash,
            uploadedAt = photo.uploadedAt,
            takenAt = photo.takenAt,
            kind = photo.kind,
            stage = photo.stage,
        )
        val nothingLeftToSend = existing.localBlobPath == null && existing.sourcePath == null
        if (photo.uploadedAt != null && nothingLeftToSend &&
            existing.uploadStatus != RemotePhotoStatus.UPLOADED
        ) {
            markPhotoUploaded(photo.id)
        }
    }

    /**
     * Смена клиентского PK на серверный одной транзакцией.
     *
     * Раньше шли подряд deletePhotoById + upsertPhoto: смерть процесса между
     * ними теряла строку целиком вместе с сохранённой локальной миниатюрой.
     */
    @Transaction
    suspend fun replaceUploadedPhotoId(oldId: String, row: RemoteDeliveryPhotoEntity) {
        if (oldId != row.id) deletePhotoById(oldId)
        upsertPhoto(row)
    }


    /**
     * Сохранённые миниатюры уже отправленных фото — кандидаты на вытеснение,
     * когда каталог перерос лимит. Только UPLOADED: у неотправленных кадров
     * миниатюра ещё нужна pipeline'у загрузки.
     */
    @Query("SELECT id, localThumbPath FROM remote_delivery_photos WHERE uploadStatus = 'UPLOADED' AND localThumbPath IS NOT NULL")
    suspend fun findUploadedLocalThumbs(): List<LocalThumbRef>

    /** После вытеснения файла путь обязан исчезнуть: иначе UI сошлётся на несуществующий файл. */
    @Query("UPDATE remote_delivery_photos SET localThumbPath = NULL WHERE id = :id")
    suspend fun clearLocalThumb(id: String)

    @Transaction
    suspend fun saveAggregate(
        delivery: RemoteDeliveryEntity,
        items: List<RemoteDeliveryItemEntity>,
        photos: List<RemoteDeliveryPhotoEntity>,
        /**
         * Кадры, снятые в форме и ещё не подготовленные. Вставляются IGNORE:
         * они принадлежат этой же транзакции (сущность + мутация + фото
         * появляются вместе), но повторная финализация не должна затирать
         * строку, которая уже ушла в подготовку или заливку.
         */
        photoIntents: List<RemoteDeliveryPhotoEntity> = emptyList(),
    ) {
        upsert(delivery)
        deleteItemsByDelivery(delivery.id)
        if (items.isNotEmpty()) replaceItems(items)
        if (photoIntents.isNotEmpty()) insertPhotoIntents(photoIntents)
        // Photo живут своим pipeline (PhotoUploadProcessor: presign → S3 → confirm),
        // и сервер на /deliveries upsert не возвращает их состояние (photos=[]).
        // Поэтому ВНУТРИ saveAggregate НЕ удаляем локальные фото — иначе
        // PENDING_UPLOAD-записи, только что созданные через captureForDelivery,
        // потеряются сразу после push мутации. Серверные фото мерджим upsert'ом
        // по id (merge без drop неприсланных).
        for (p in photos) upsertServerPhoto(p)
    }

    @Query("SELECT * FROM remote_deliveries WHERE id = :id")
    suspend fun findById(id: String): RemoteDeliveryEntity?

    @Query("SELECT * FROM remote_deliveries WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<RemoteDeliveryEntity>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE pendingDeletionAt IS NULL
        ORDER BY arrivedAt DESC, createdAt DESC
        """
    )
    fun observeActive(): Flow<List<RemoteDeliveryEntity>>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE pendingDeletionAt IS NOT NULL
        ORDER BY pendingDeletionAt DESC
        """
    )
    fun observeTrash(): Flow<List<RemoteDeliveryEntity>>

    @Query("DELETE FROM remote_deliveries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Полная очистка серверного snapshot — для smart-reset при смене
     * user.siteId в админке. items/photos удалятся каскадом по FK с
     * onDelete=CASCADE. См. SiteChangeReset.resumeIfNeeded.
     */
    @Query("DELETE FROM remote_deliveries")
    suspend fun deleteAll()

    /**
     * Частичная очистка snapshot при смене объекта: сносим всё, кроме
     * защищённых записей (карантин чужого объекта, неотправленные фото,
     * непустая очередь мутаций). Каскад по FK удалит items и photos.
     *
     * Пустой [keepIds] даёт `NOT IN ()` — SQLite это разрешает, условие
     * истинно для всех строк, поведение совпадает с [deleteAll].
     */
    @Query("DELETE FROM remote_deliveries WHERE id NOT IN (:keepIds)")
    suspend fun deleteAllExcept(keepIds: List<String>): Int

    /** id приёмок, у которых есть хотя бы одно фото, не доехавшее на сервер. */
    @Query("SELECT DISTINCT deliveryId FROM remote_delivery_photos WHERE uploadStatus <> 'UPLOADED'")
    suspend fun findParentIdsWithUnsentPhotos(): List<String>

    @Query("SELECT id FROM remote_deliveries WHERE conflictPending = 1")
    suspend fun listConflictPendingIds(): List<String>

    // Лёгкая проекция (id, version) всех локальных приёмок — для reconcile-сверки
    // (read-only). См. SyncRepository.reconcileOnce.
    @Query("SELECT id, version FROM remote_deliveries")
    suspend fun listReconcileVersions(): List<ReconcileVersionRow>

    @Query("SELECT * FROM remote_deliveries WHERE conflictPending = 1 ORDER BY updatedAt DESC")
    fun observeConflicts(): kotlinx.coroutines.flow.Flow<List<RemoteDeliveryEntity>>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE statusCode = :status AND pendingDeletionAt IS NULL
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeByStatus(status: String): Flow<List<RemoteDeliveryEntity>>

    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE statusCode IN (:statuses) AND pendingDeletionAt IS NULL
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeByStatuses(statuses: List<String>): Flow<List<RemoteDeliveryEntity>>

    /**
     * Активная (не помеченная на удаление) приёмка по natural key
     * siteId + statusCode + sourceDocumentIdsJson. Используется в
     * [DeliveryRepository.upsert] для дедупликации при повторных
     * finalizeStage1 после ошибки фото / process kill / double-tap.
     *
     * ORDER BY updatedAt DESC — если в БД уже есть legacy-дубли до
     * этого фикса, детерминированно выбираем самую свежую (не «какая
     * попалась» из SQLite). Для новых записей natural key уникален.
     */
    @Query(
        """
        SELECT * FROM remote_deliveries
        WHERE siteId = :siteId
            AND statusCode = :statusCode
            AND sourceDocumentIdsJson = :sourceDocumentIdsJson
            AND pendingDeletionAt IS NULL
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun findByNaturalKey(
        siteId: String,
        statusCode: String,
        sourceDocumentIdsJson: String,
    ): RemoteDeliveryEntity?

    @Query("SELECT * FROM remote_delivery_items WHERE deliveryId = :deliveryId ORDER BY lineNo ASC")
    suspend fun findItemsByDelivery(deliveryId: String): List<RemoteDeliveryItemEntity>

    @Query("SELECT * FROM remote_delivery_photos WHERE deliveryId = :deliveryId ORDER BY takenAt ASC")
    suspend fun findPhotosByDelivery(deliveryId: String): List<RemoteDeliveryPhotoEntity>

    @Query(
        """
        SELECT sourceDocumentIdsJson FROM remote_deliveries
        WHERE pendingDeletionAt IS NULL AND sourceDocumentIdsJson != '[]'
        """
    )
    fun observeAttachedSourceDocumentIdsJson(): Flow<List<String>>
}
