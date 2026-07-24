package com.example.matcheckmobile.domain

/**
 * Единая политика статусов приёмок/отгрузок для sync-логики.
 *
 * `confirmed_mol` («Подтверждено МОЛ») — терминальный статус: запись
 * финализирована и уходит в Архив. Терминал одинаков для delivery и shipment
 * (см. DeliveryDto/ShipmentDto: '... | confirmed_mol').
 *
 * Используется server-win-разрешением терминальных OCC-конфликтов
 * (MutationProcessor, TerminalConflictResolver): к записи, которую сервер уже
 * перевёл в терминал, замороженная локальная правка недоставляема, поэтому
 * конфликт снимается в пользу сервера.
 */
object StatusPolicy {
    const val CONFIRMED_MOL = "confirmed_mol"

    fun isTerminal(code: String?): Boolean = code == CONFIRMED_MOL
}
