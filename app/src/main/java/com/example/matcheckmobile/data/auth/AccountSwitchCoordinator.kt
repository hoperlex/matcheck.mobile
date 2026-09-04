package com.example.matcheckmobile.data.auth

import android.content.Context
import android.util.Log
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.remote.sse.SseConnectionManager
import com.example.matcheckmobile.data.repository.SyncRepository
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.media.PhotoFrameCache
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Смена аккаунта на устройстве: гарантирует, что локальные данные прошлого
 * пользователя не будут ни отправлены под токеном нового, ни потеряны молча.
 *
 * Зачем. Раньше вход другим логином не трогал Room: чистила только кнопка
 * «Выйти», а автоматический разлогин стирал лишь токены. Планшет, побывавший
 * под аккаунтом объекта А, после входа под аккаунтом объекта Б переотправлял
 * его записи, и сервер переклеивал им объект. Инцидент 2026-07: 287 приёмок и
 * 141 отгрузка ЗИЛ33 уехали на TEST.
 *
 * Порядок шагов закрывает гонку login ↔ WorkManager: между сохранением новых
 * токенов и очисткой базы периодический sync успевал отправить чужие записи
 * уже под новой сессией.
 *
 * 1. гасим SSE (его события дёргают немедленный sync);
 * 2. отменяем и дожидаемся снятия sync-задач WorkManager;
 * 3. берём sync-мьютекс — дожидаемся уже идущего syncOnce;
 * 4. внутри него чистим Room, sync-курсор и каталоги с фото.
 *
 * [SessionGate] на всё время логина поднимает `AuthRepository.login`.
 *
 * Данные удаляются ТОЛЬКО когда удалять нечего либо человек это явно
 * подтвердил. Все проверки **fail-closed**: сбой подсчёта трактуется как
 * «неотправленное есть», а не как «чисто».
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
     * Чистим ТОЛЬКО при реальной смене: сохранённый id непустой и отличается от
     * нового. Пустой id — первый вход после установки (или после «Выйти»,
     * которая уже всё почистила).
     *
     * Сравнение именно по **id**, а не по email: id известен только после
     * ответа сервера, зато он канонический. Предварительный диалог на экране
     * логина работает по email — это оптимизация, а барьер здесь.
     *
     * @throws AccountSwitchBlocked если есть неотправленные данные без согласия.
     * @return true, если очистка выполнялась.
     */
    suspend fun prepareForLogin(newUserId: String, confirmedWipe: Boolean = false): Boolean {
        val previousUserId = deviceSettings.currentUserIdFlow.first()
        if (previousUserId.isEmpty() || previousUserId == newUserId) return false

        val pending = pendingWork()
        if (!pending.isEmpty && !confirmedWipe) throw AccountSwitchBlocked(pending)

        val outcome = wipeAccountData()
        if (!outcome.isComplete) throw AccountWipeFailed(outcome)
        Log.i(TAG, "account switch: local data wiped ($previousUserId → $newUserId)")
        return true
    }

    /**
     * Что на планшете ещё не доехало до сервера. Классификация таблиц и
     * fail-closed-семантика — в [PendingWorkProbe].
     */
    suspend fun pendingWork(): PendingWork = PendingWorkProbe(database).probe()

    /**
     * Полная очистка данных аккаунта. Одна процедура и для смены аккаунта, и
     * для кнопки «Выйти» — раньше они расходились, и logout оставлял на диске
     * jpeg'и прошлого инспектора (`clearAllTables` файлы не трогает).
     *
     * Токены здесь НЕ трогаем: при смене аккаунта их сразу перезапишет новая
     * сессия, при выходе это делает `AuthRepository.logout()` — но только если
     * очистка удалась.
     *
     * Незавершённость фиксируется персистентно: файлы могли не удалиться
     * (заняты, ошибка ФС), и об этом раньше никто не узнавал — `runCatching`
     * глотал сбой внутри. Отметка снимается лишь после полного успеха, а
     * [resumePendingWipe] доделывает остаток на следующем старте.
     */
    suspend fun wipeAccountData(): WipeOutcome {
        sseConnectionManager.stop()
        // Барьер ограничен по времени. Без таймаута выход намертво зависал без
        // единого признака на экране: sync-мьютекс держит идущий syncOnce, а
        // PUT фото в S3 идёт блокирующим вызовом и отмену не замечает — до двух
        // минут на снимок. Лучше честно сказать «не удалось, попробуйте ещё
        // раз», чем притвориться, что кнопка не нажималась.
        val outcome = withTimeoutOrNull(BARRIER_TIMEOUT_MS) {
            MatcheckSyncScheduler.cancelSyncWorkAndAwait(appContext)
            syncRepository.runExclusively {
                deviceSettings.setWipePending(true)
                val failedFiles = withContext(Dispatchers.IO) {
                    database.clearAllTables()
                    deletePhotoDirectories()
                }
                deviceSettings.clearSyncCursor()
                deviceSettings.clearPendingSiteReset()
                syncRepository.resetReconcileThrottle()
                if (failedFiles == 0) deviceSettings.setWipePending(false)
                WipeOutcome(failedFiles = failedFiles)
            }
        }
        if (outcome == null) Log.w(TAG, "wipe: не дождались завершения синхронизации за ${BARRIER_TIMEOUT_MS}мс")
        // Таймаут — fail-closed: сессию не рвём, данные на месте, человек
        // повторит, когда синхронизация закончится.
        return outcome ?: WipeOutcome(failedFiles = -1)
    }

    /**
     * Доделывает очистку, прерванную в прошлый раз (файлы не удалились или
     * процесс умер посреди wipe). Room к этому моменту уже пуст — остаются
     * только файлы.
     */
    suspend fun resumePendingWipe(): Boolean {
        if (!deviceSettings.isWipePending()) return false
        val failed = withContext(Dispatchers.IO) { deletePhotoDirectories() }
        if (failed == 0) {
            deviceSettings.setWipePending(false)
            Log.i(TAG, "незавершённая очистка доделана")
        } else {
            Log.w(TAG, "очистка всё ещё неполная: осталось файлов $failed")
        }
        return failed == 0
    }

    /**
     * Файлы фото живут в filesDir и переживают `clearAllTables()`: строки
     * исчезают, а jpeg'и остаются на диске снимками прошлого инспектора.
     *
     * @return сколько файлов удалить НЕ удалось.
     */
    private fun deletePhotoDirectories(): Int {
        var failed = 0
        // Кадры прошлого инспектора не должны пережить выход и в кэшах:
        // дисковый кэш скачанных фото лежит в cacheDir, декодированные
        // миниатюры — в памяти процесса.
        PhotoFrameCache.clear()
        runCatching { File(appContext.cacheDir, "photo_frames").deleteRecursively() }
        for (name in PHOTO_DIRS) {
            val dir = File(appContext.filesDir, name)
            if (!dir.isDirectory) continue
            val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in entries) {
                val ok = runCatching { file.deleteRecursively() }.getOrDefault(false)
                if (!ok && file.exists()) failed++
            }
        }
        return failed
    }

    /**
     * Итог очистки. Неполная очистка — не повод стирать сессию.
     * [failedFiles] < 0 — не дождались барьера синхронизации (таймаут).
     */
    data class WipeOutcome(val failedFiles: Int) {
        val isComplete: Boolean get() = failedFiles == 0
        val timedOut: Boolean get() = failedFiles < 0

        fun describe(): String = when {
            timedOut -> "синхронизация ещё идёт — попробуйте через минуту"
            failedFiles > 0 -> "не удалось удалить файлов: $failedFiles"
            else -> "очистка завершена"
        }
    }

    private companion object {
        const val TAG = "AccountSwitch"

        /**
         * Потолок ожидания барьера. 90 с — с запасом перекрывает writeTimeout
         * одного PUT фото в S3 (120 с делится на попытки), но не превращает
         * кнопку выхода в чёрную дыру.
         */
        const val BARRIER_TIMEOUT_MS = 90_000L
        val PHOTO_DIRS = listOf("remote_photos", "operation_photos")
    }
}

/**
 * Вход другим аккаунтом отклонён: на планшете есть работа прошлого
 * пользователя, которую удалять без спроса нельзя. Несёт [pending] целиком,
 * чтобы UI мог показать тот же диалог с выбором, а не сухую строку ошибки.
 */
class AccountSwitchBlocked(
    val pending: PendingWork,
) : IllegalStateException("account switch blocked: ${pending.describe()}")

/** Очистка не завершилась полностью — сессию не трогаем, иначе данные осиротеют. */
class AccountWipeFailed(
    val outcome: AccountSwitchCoordinator.WipeOutcome,
) : IllegalStateException("account wipe incomplete: ${outcome.failedFiles} files left")
