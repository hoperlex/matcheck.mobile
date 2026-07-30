package com.example.matcheckmobile.data.auth

import android.content.Context
import android.util.Log
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.remote.sse.SseConnectionManager
import com.example.matcheckmobile.data.repository.SyncRepository
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Смена аккаунта на устройстве: гарантирует, что локальные данные прошлого
 * пользователя не будут ни отправлены под токеном нового, ни потеряны молча.
 *
 * Зачем. Раньше вход другим логином не трогал Room: чистила только кнопка
 * «Выйти» (SettingsViewModel), а автоматический разлогин (401 / инвалидация
 * сессии) стирал лишь токены. В результате планшет, побывавший под аккаунтом
 * объекта А, после входа под аккаунтом объекта Б переотправлял его записи —
 * и сервер переклеивал им объект. Инцидент 2026-07: 287 приёмок и 141
 * отгрузка ЗИЛ33 уехали на TEST.
 *
 * Порядок шагов важен и закрывает гонку login ↔ WorkManager: между сохранением
 * новых токенов и очисткой базы периодический sync успевал отправить чужие
 * записи уже под новой сессией.
 *
 * 1. гасим SSE (его события дёргают немедленный sync);
 * 2. отменяем и дожидаемся снятия sync-задач WorkManager;
 * 3. берём sync-мьютекс — дожидаемся уже идущего syncOnce;
 * 4. внутри него чистим Room, sync-курсор и каталоги с фото.
 *
 * [SessionGate] на всё время логина (включая активацию новой сессии) поднимает
 * `AuthRepository.login` — иначе между очисткой и сохранением токенов воркер
 * успел бы наполнить базу данными ещё старого аккаунта.
 *
 * Данные удаляются ТОЛЬКО когда удалять нечего либо человек это явно
 * подтвердил ([prepareForLogin] с `confirmedWipe = true`). Без подтверждения
 * вход отклоняется с [AccountSwitchBlocked], старая сессия остаётся в силе, и
 * инспектор может сначала синхронизироваться под своим логином.
 */
class AccountSwitchCoordinator(
    private val appContext: Context,
    private val database: MatcheckDatabase,
    private val deviceSettings: DeviceSettings,
    private val syncRepository: SyncRepository,
    private val sseConnectionManager: SseConnectionManager,
) {

    /**
     * Готовит устройство ко входу пользователя [newUserId].
     *
     * Чистим ТОЛЬКО при реальной смене: сохранённый id непустой и отличается
     * от нового. Пустой id — первый вход после установки (или после «Выйти»,
     * которая уже всё почистила), и трогать базу нельзя: там могут лежать
     * незалитые фото и черновики текущего инспектора.
     *
     * @param confirmedWipe человек согласился потерять неотправленное.
     * @throws AccountSwitchBlocked если на планшете есть неотправленные данные,
     *   а подтверждения нет. Вызывается из `AuthRepository.login` ДО сохранения
     *   токенов, поэтому исключение просто отменяет вход.
     * @return true, если очистка выполнялась.
     */
    suspend fun prepareForLogin(newUserId: String, confirmedWipe: Boolean = false): Boolean {
        val previousUserId = deviceSettings.currentUserIdFlow.first()
        if (previousUserId.isEmpty() || previousUserId == newUserId) return false

        // Барьер на случай, если UI не спросил (автологин, гонка, чужая точка
        // входа): проверяем очередь прямо здесь, у самой активации сессии.
        val pending = pendingWork()
        if (!pending.isEmpty && !confirmedWipe) throw AccountSwitchBlocked(pending)

        wipeAccountData()
        Log.i(TAG, "account switch: local data wiped ($previousUserId → $newUserId)")
        return true
    }

    /**
     * Что на планшете ещё не доехало до сервера. Считается по «сырым» данным,
     * а не по индикатору синхронизации: пользователю показываем конкретные
     * числа, прежде чем предлагать удаление.
     */
    suspend fun pendingWork(): PendingWork {
        val mutationDao = database.mutationDao()
        val deliveryDao = database.remoteDeliveryDao()
        val shipmentDao = database.remoteShipmentDao()
        val mutations = runCatching {
            mutationDao.listPending(Long.MAX_VALUE).size + mutationDao.listConflicts().size
        }.getOrDefault(0)
        val photos = runCatching {
            deliveryDao.findPhotosByStatus(UNSENT_PHOTO_STATUSES).size +
                shipmentDao.findPhotosByStatus(UNSENT_PHOTO_STATUSES).size
        }.getOrDefault(0)
        val quarantined = runCatching {
            deliveryDao.findPhotosByStatus(QUARANTINE_ONLY).size +
                shipmentDao.findPhotosByStatus(QUARANTINE_ONLY).size
        }.getOrDefault(0)
        val drafts = runCatching {
            database.stage1DraftDao().count() +
                database.stage2DraftDao().count() +
                database.shipmentStage1DraftDao().count() +
                database.shipmentStage2DraftDao().count()
        }.getOrDefault(0)
        return PendingWork(
            mutations = mutations,
            photos = photos,
            drafts = drafts,
            quarantinedPhotos = quarantined,
        )
    }

    /**
     * Полная очистка данных аккаунта. Одна процедура и для смены аккаунта, и
     * для кнопки «Выйти» — раньше они расходились, и logout оставлял на диске
     * jpeg'и прошлого инспектора (`clearAllTables` файлы не трогает).
     *
     * Токены здесь НЕ трогаем: при смене аккаунта их сразу перезапишет новая
     * сессия, а при выходе это делает `AuthRepository.logout()` — уже после
     * очистки, чтобы UI не увидел пустую базу под старой сессией.
     */
    suspend fun wipeAccountData() {
        sseConnectionManager.stop()
        MatcheckSyncScheduler.cancelSyncWorkAndAwait(appContext)
        syncRepository.runExclusively {
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                deletePhotoDirectories()
            }
            deviceSettings.clearSyncCursor()
            deviceSettings.clearPendingSiteReset()
        }
    }

    /**
     * Файлы фото живут в filesDir и переживают `clearAllTables()`: строки
     * исчезают, а jpeg'и остаются мусором на диске (и, что важнее, снимками
     * прошлого инспектора на чужом планшете).
     */
    private fun deletePhotoDirectories() {
        for (name in PHOTO_DIRS) {
            val dir = File(appContext.filesDir, name)
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { file -> runCatching { file.deleteRecursively() } }
        }
    }

    /** Неотправленное на планшете — то, что пропадёт при очистке. */
    data class PendingWork(
        val mutations: Int,
        /** Снимки, которые ещё можно доставить обычной синхронизацией. */
        val photos: Int,
        val drafts: Int,
        /**
         * Карантин чужого объекта. Считается отдельно: синхронизация его не
         * вылечит (сервер отвечает 403 всегда), решается только руками в
         * «Очереди синхронизации» — сохранить и удалить.
         */
        val quarantinedPhotos: Int,
    ) {
        val isEmpty: Boolean
            get() = mutations == 0 && photos == 0 && drafts == 0 && quarantinedPhotos == 0

        /** Можно ли надеяться, что синхронизация очистит очередь. */
        val syncCanHelp: Boolean get() = mutations > 0 || photos > 0

        /** Человекочитаемое перечисление для диалога подтверждения. */
        fun describe(): String = buildList {
            if (mutations > 0) add("записей в очереди: $mutations")
            if (photos > 0) add("незагруженных фото: $photos")
            if (drafts > 0) add("черновиков: $drafts")
            if (quarantinedPhotos > 0) {
                add("фото чужого объекта: $quarantinedPhotos (только вручную, в «Очереди синхронизации»)")
            }
        }.joinToString(", ")
    }

    private companion object {
        const val TAG = "AccountSwitch"
        val PHOTO_DIRS = listOf("remote_photos", "operation_photos")
        val UNSENT_PHOTO_STATUSES = listOf(
            RemotePhotoStatus.PENDING_UPLOAD,
            RemotePhotoStatus.UPLOADING,
            RemotePhotoStatus.UPLOAD_ERROR,
        )
        val QUARANTINE_ONLY = listOf(RemotePhotoStatus.QUARANTINED_FOREIGN_SITE)
    }
}

/**
 * Вход другим аккаунтом отклонён: на планшете есть работа прошлого
 * пользователя, которую удалять без спроса нельзя.
 */
class AccountSwitchBlocked(
    val pending: AccountSwitchCoordinator.PendingWork,
) : IllegalStateException("account switch blocked: ${pending.describe()}")
