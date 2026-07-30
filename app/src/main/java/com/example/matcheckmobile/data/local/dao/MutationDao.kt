package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.MutationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MutationDao {

    @Upsert
    suspend fun upsert(entity: MutationEntity)

    // Готовые к отправке мутации: не ждут разрешения конфликта (conflictPending=0)
    // и не находятся в backoff-паузе (nextAttemptAt в прошлом/не задан). Фильтр
    // по nextAttemptAt — чтобы застрявшая на transient-ошибке мутация не долбила
    // сервер на каждом sync, а уважала экспоненциальный backoff.
    @Query(
        "SELECT * FROM mutations WHERE conflictPending = 0 " +
            "AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) ORDER BY createdAt ASC",
    )
    suspend fun listPending(now: Long): List<MutationEntity>

    @Query("SELECT * FROM mutations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MutationEntity>>

    @Query("SELECT * FROM mutations WHERE conflictPending = 1 ORDER BY createdAt ASC")
    suspend fun listConflicts(): List<MutationEntity>

    @Query("SELECT * FROM mutations WHERE entityType = :type AND entityId = :entityId")
    suspend fun findFor(type: String, entityId: String): List<MutationEntity>

    @Query("DELETE FROM mutations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM mutations WHERE entityType = :type AND entityId = :entityId")
    suspend fun deleteFor(type: String, entityId: String)

    /**
     * Сущности с непустой очередью — их нельзя сносить при partial-reset
     * смены объекта: мутация осталась бы без локальной записи.
     */
    @Query("SELECT DISTINCT entityId FROM mutations WHERE entityType = :type")
    suspend fun listEntityIdsWithQueue(type: String): List<String>

    /** Мутации, чей lastError указывает на 403 foreign_site (наследие 1.0.29). */
    @Query("SELECT * FROM mutations WHERE lastError LIKE '%foreign_site%'")
    suspend fun listForeignSiteFailures(): List<MutationEntity>

    @Query("SELECT COUNT(*) FROM mutations WHERE conflictPending = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM mutations WHERE conflictPending = 0")
    fun observePendingCount(): Flow<Int>
}
