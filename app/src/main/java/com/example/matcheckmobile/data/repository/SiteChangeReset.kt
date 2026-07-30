package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.settings.DeviceSettings

/**
 * Частичный сброс серверного snapshot при смене объекта у аккаунта.
 *
 * Сценарий: администратор перевёл инспектора с ЗИЛ33 на ИНДЖОЙ. Приёмки,
 * отгрузки и УПД в Room относятся к прошлому объекту — их надо перечитать.
 *
 * Три свойства, каждое из которых закрывает реальный дефект:
 *
 *  - **долг персистентный и пишется ПЕРВЫМ.** Порядок строгий: `/me` читает
 *    новый siteId, не трогая TokenStorage → [markPending] → и только затем
 *    вызывающий сохраняет siteId в токене. Раньше было наоборот, и смерть
 *    процесса между шагами навсегда прятала смену объекта: следующий `/me`
 *    возвращал уже сохранённое значение, «изменений нет», сброс не случался.
 *    Обрыв между долгом и токеном чинит [resumeIfNeeded] — он досохраняет
 *    siteId сам;
 *  - **сброс частичный.** Записи с несохранённым (карантин, неотправленные
 *    фото, непустая очередь мутаций) остаются: каскад по FK иначе снёс бы их
 *    вместе с родителем;
 *  - **список защищённых читается ВНУТРИ транзакции.** При чтении снаружи фото,
 *    снятое в окне между проверкой и удалением, не попадало в `keepIds` и
 *    уезжало каскадом.
 *
 * Порядок внутри [resumeIfNeeded] важен: сначала курсор, потом удаление. При
 * обратном порядке падение между шагами оставило бы пустую базу со старым
 * курсором — дельта-sync не вернул бы «уже прочитанные» записи.
 *
 * Вызывающий обязан обеспечить, что [resumeIfNeeded] не идёт параллельно с
 * синхронизацией (sync-мьютекс). Из `onBeforeSync` мьютекс уже взят, со старта
 * приложения его берёт AppContainer.
 */
class SiteChangeReset(
    private val deviceSettings: DeviceSettings,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val sourceDocumentDao: RemoteSourceDocumentDao,
    private val mutationDao: MutationDao,
    private val quarantine: ForeignSiteQuarantine,
    private val tx: TransactionRunner,
    /** Объект в текущей сессии (TokenStorage). Нужен для сверки на старте. */
    private val tokenSiteId: () -> String?,
    /** Досохранение siteId в токене, если процесс умер между долгом и токеном. */
    private val persistTokenSiteId: (String) -> Unit,
    /** Сбросить minute-throttle reconcile: после сброса хвост нужен сразу. */
    private val onResetDone: () -> Unit = {},
) {

    /**
     * Фиксирует намерение сбросить snapshot под новый объект.
     * Вызывать СТРОГО до сохранения нового siteId в TokenStorage: долг без
     * смены безвреден (сброс идемпотентен), смена без долга — необратима.
     */
    suspend fun markPending(newSiteId: String) {
        deviceSettings.beginSiteChange(newSiteId)
    }

    /**
     * Доделывает незавершённый сброс. Идемпотентно: без долга — no-op.
     *
     * @return true, если сброс был выполнен в этом вызове.
     */
    suspend fun resumeIfNeeded(): Boolean {
        val target = deviceSettings.readPendingSiteReset() ?: return false

        // Долг есть, а токен ещё на старом объекте — значит процесс умер между
        // markPending и сохранением siteId. Доводим протокол до конца сами,
        // иначе списки продолжат фильтроваться по прошлому объекту.
        if (tokenSiteId() != target) {
            runCatching { persistTokenSiteId(target) }
        }

        // 1. Курсор — первым. Дальше любое падение приведёт лишь к повторному
        // initial-sync, а не к «пусто, но курсор считает, что всё прочитано».
        deviceSettings.clearSyncCursor()

        // 2. Одна транзакция, и защищённые id читаются ВНУТРИ неё: снимок,
        // сделанный до транзакции, не увидел бы работу, начатую в этот момент.
        tx.run {
            val keepDeliveries = protectedDeliveryIds()
            val keepShipments = protectedShipmentIds()
            sourceDocumentDao.deleteAll()
            deliveryDao.deleteAllExcept(keepDeliveries)
            shipmentDao.deleteAllExcept(keepShipments)
        }

        deviceSettings.clearPendingSiteReset()
        runCatching { onResetDone() }
        return true
    }

    /**
     * Записи, которые сброс обязан пропустить: у них на планшете есть работа,
     * которой нет на сервере.
     */
    private suspend fun protectedDeliveryIds(): List<String> {
        val quarantined = quarantine.protectedParents().deliveryIds
        val unsentPhotos = deliveryDao.findParentIdsWithUnsentPhotos()
        val queued = mutationDao.listEntityIdsWithQueue(ForeignSiteQuarantine.ENTITY_DELIVERY)
        return (quarantined + unsentPhotos + queued).distinct()
    }

    private suspend fun protectedShipmentIds(): List<String> {
        val quarantined = quarantine.protectedParents().shipmentIds
        val unsentPhotos = shipmentDao.findParentIdsWithUnsentPhotos()
        val queued = mutationDao.listEntityIdsWithQueue(ForeignSiteQuarantine.ENTITY_SHIPMENT)
        return (quarantined + unsentPhotos + queued).distinct()
    }
}
