package com.example.matcheckmobile.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.matcheckmobile.MatcheckApplication
import java.util.concurrent.TimeUnit

/**
 * Готовит снятые кадры к отправке: `PENDING_PREPARE → PENDING_UPLOAD`.
 *
 * Отдельный воркер, а не шаг внутри [MatcheckSyncWorker], именно из-за
 * constraints: у синка стоит `NetworkType.CONNECTED`, и за долгий офлайн
 * (на «ЖК Событие 6.1» это были четверо суток) подготовка не запустилась бы
 * ни разу — кадры лежали бы неподготовленными до самого возвращения сети.
 * Здесь constraints нет вообще: работа полностью локальная.
 */
class PhotoPrepareWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MatcheckApplication).container
        val result = container.photoPrepareProcessor.processAll()
        if (result.prepared > 0 || result.failed > 0) {
            Log.i(TAG, "prepare: готово ${result.prepared}, ошибок ${result.failed}, пропущено ${result.skipped}")
        }
        // Подготовленный кадр обязан сам позвать заливку.
        //
        // Финализация формы дёргает оба воркера сразу, но в тот момент кадры
        // ещё PENDING_PREPARE: у них нет localBlobPath, и upload-цикл их не
        // видит (findPhotosByStatus(UPLOADABLE)). Пока этой строки не было,
        // готовые кадры ждали СЛЕДУЮЩЕГО постороннего триггера — SSE, чужой
        // финализации или 15-минутной периодики. На бою это давало 25 секунд
        // между «Завершить 2 Этап» и первой фотографией на сервере (приёмка
        // 11786, 24.08: нажатие 13:32:32, первый кадр 13:32:57), а сама
        // приёмка уезжала ещё позже — она стоит в очереди за фото.
        //
        // Только при prepared > 0: у неудачных кадров blob'а нет, звать
        // заливку незачем, их повторит периодика.
        if (result.prepared > 0) {
            MatcheckSyncScheduler.requestImmediateSync(applicationContext)
        }
        // Ошибку не считаем провалом задачи: строки остались в PREPARE_ERROR и
        // будут повторены — периодикой, следующей финализацией или кнопкой
        // «Повторить». Result.retry() здесь только удлинял бы backoff.
        return Result.success()
    }

    private companion object {
        const val TAG = "PhotoPrepareWorker"
    }
}

object PhotoPrepareScheduler {
    const val ONE_TIME = "matcheck_photo_prepare_once"
    const val PERIODIC = "matcheck_photo_prepare_periodic"

    /**
     * Подготовить кадры сейчас. Дёргается после каждой финализации формы,
     * при старте приложения и из кнопки «Повторить сейчас».
     *
     * KEEP: если подготовка уже идёт, второй запуск не нужен — процессор
     * забирает все PREPARABLE-строки разом, а lease защищает от гонки.
     */
    fun requestPrepare(context: Context) {
        val request = OneTimeWorkRequestBuilder<PhotoPrepareWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Backstop на случай, когда подготовка упала по нехватке места или памяти:
     * место освободится (кадры доедут и удалятся), и повтор должен случиться
     * сам, без участия инспектора. 15 минут — минимум WorkManager.
     */
    fun schedulePeriodicPrepare(context: Context) {
        val request = PeriodicWorkRequestBuilder<PhotoPrepareWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
