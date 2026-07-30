package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.settings.DeviceSettings

/**
 * Частичный сброс серверного snapshot при смене объекта у аккаунта.
 *
 * Сценарий: администратор перевёл инспектора с ЗИЛ33 на ИНДЖОЙ (или в офисе
 * зашли под другим аккаунтом). Приёмки, отгрузки и УПД в Room относятся к
 * прошлому объекту — их надо перечитать заново. Раньше это делалось
 * `deleteAll()` по флагу в памяти процесса; здесь два отличия:
 *
 *  - **долг персистентный.** Намерение пишется в DataStore вместе с новым
 *    siteId ([DeviceSettings.beginSiteChange]) и снимается только после
 *    успешного сброса. Убийство процесса в середине больше не оставляет
 *    планшет со снимком чужого объекта навсегда;
 *  - **сброс частичный.** Записи, у которых на планшете есть несохранённое
 *    (карантинные снимки, неотправленные фото, непустая очередь мутаций),
 *    остаются. Каскад по FK иначе снёс бы их вместе с родителем.
 *
 * Порядок внутри [resumeIfNeeded] важен: сначала курсор, потом удаление.
 * При обратном порядке падение между шагами оставило бы пустую базу со
 * старым курсором — дельта-sync не вернул бы уже «прочитанные» записи, и
 * на планшете не оказалось бы вообще ничего.
 */
class SiteChangeReset(
    private val deviceSettings: DeviceSettings,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val sourceDocumentDao: RemoteSourceDocumentDao,
    private val mutationDao: MutationDao,
    private val quarantine: ForeignSiteQuarantine,
    private val tx: TransactionRunner,
) {

    /** Фиксирует намерение сбросить snapshot под новый объект. */
    suspend fun markPending(newSiteId: String) {
        deviceSettings.beginSiteChange(newSiteId)
    }

    /**
     * Доделывает незавершённый сброс. Идемпотентно: без долга — no-op, при
     * повторном вызове после успеха тоже no-op.
     *
     * @return true, если сброс был выполнен в этом вызове.
     */
    suspend fun resumeIfNeeded(): Boolean {
        deviceSettings.readPendingSiteReset() ?: return false

        // 1. Курсор — первым. Дальше любое падение приведёт лишь к повторному
        // initial-sync, а не к «пусто, но курсор считает, что всё прочитано».
        deviceSettings.clearSyncCursor()

        // 2. Одна транзакция: либо snapshot прошлого объекта ушёл целиком,
        // либо не ушёл вовсе и долг переиграется на следующем старте.
        val keepDeliveries = protectedDeliveryIds()
        val keepShipments = protectedShipmentIds()
        tx.run {
            sourceDocumentDao.deleteAll()
            deliveryDao.deleteAllExcept(keepDeliveries)
            shipmentDao.deleteAllExcept(keepShipments)
        }

        deviceSettings.clearPendingSiteReset()
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
