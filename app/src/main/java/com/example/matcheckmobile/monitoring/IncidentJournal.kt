package com.example.matcheckmobile.monitoring

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.matcheckmobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Узкий приёмник диагностических событий. Отдельный интерфейс нужен, чтобы
 * навигация и контроллер оверлея не тянули за собой файловый I/O: в тестах
 * подставляется список в памяти и проверка не ждёт диск.
 */
interface IncidentRecorder {
    fun record(kind: String, vararg fields: Pair<String, Any?>)
}

/**
 * Долговечный журнал инцидентов на диске.
 *
 * Зачем файл, а не таблица Room: миграции БД в проекте ручные и подстрахованы
 * `fallbackToDestructiveMigration(dropAllTables = true)` (см. MatcheckDatabase).
 * Ошибка в миграции ради диагностики стоила бы несинхронизированных черновиков
 * и очереди мутаций — цена несопоставима с пользой.
 *
 * Зачем вообще: logcat до планшетов инспекторов недоступен, Sentry в проде
 * выключен (пустой DSN), эндпоинта для клиентских логов на бэкенде нет.
 * Журнал забирается вручную через «Поделиться» в «Очереди синхронизации».
 *
 * ПДн сюда не попадают: только маршруты, этапы, флаги, таймстемпы, версия
 * сборки и модель устройства. Ни госномеров, ни ФИО, ни токенов.
 */
class IncidentJournal(
    appContext: Context,
    private val scope: CoroutineScope,
) : IncidentRecorder {

    private val dir = File(appContext.filesDir, DIR_NAME)
    private val file = File(dir, FILE_NAME)
    private val backup = File(dir, "$FILE_NAME.1")

    /**
     * Dispatchers.IO многопоточный, поэтому параллельные record(), ротация и
     * подготовка экспорта могли бы пересечься на одном файле. Сериализуем всё
     * двумя способами сразу: один поток и один мьютекс поверх него.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()

    override fun record(kind: String, vararg fields: Pair<String, Any?>) {
        val line = buildLine(kind, fields)
        scope.launch { append(line) }
    }

    /** Тот же record, но с ожиданием записи — для стартового отчёта о выходе. */
    suspend fun recordAwait(kind: String, vararg fields: Pair<String, Any?>) {
        append(buildLine(kind, fields))
    }

    /**
     * Файлы журнала для экспорта, новейший первым. Пустой список — журнала ещё
     * нет. Читается под тем же мьютексом, что и запись, чтобы не отдать наружу
     * файл в момент ротации.
     */
    suspend fun exportableFiles(): List<File> = withContext(writer) {
        lock.withLock {
            listOf(file, backup).filter { it.exists() && it.length() > 0 }
        }
    }

    private fun buildLine(kind: String, fields: Array<out Pair<String, Any?>>): String {
        val json = JSONObject()
        json.put("at", System.currentTimeMillis())
        json.put("kind", kind)
        json.put("app", BuildConfig.VERSION_NAME)
        json.put("build", BuildConfig.VERSION_CODE)
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        return json.toString()
    }

    private suspend fun append(line: String) = withContext(writer) {
        lock.withLock {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                if (file.length() > MAX_BYTES) rotate()
                file.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "journal append failed", it) }
        }
    }

    /** Одно поколение бэкапа: старый .1 затирается, текущий становится .1. */
    private fun rotate() {
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    private companion object {
        const val TAG = "IncidentJournal"
        const val DIR_NAME = "diag"
        const val FILE_NAME = "incidents.log"
        const val MAX_BYTES = 64L * 1024
    }
}
