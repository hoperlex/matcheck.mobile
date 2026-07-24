package com.example.matcheckmobile.data.repository

import androidx.room.withTransaction
import com.example.matcheckmobile.data.local.MatcheckDatabase

/**
 * Узкий шов для атомарного выполнения нескольких DAO-операций в одной
 * транзакции. Прод — [RoomTransactionRunner] поверх `Room.withTransaction`;
 * в чистых JVM-тестах подставляется fake, просто выполняющий блок, чтобы не
 * тянуть Room/Android.
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactionRunner(
    private val database: MatcheckDatabase,
) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T =
        database.withTransaction { block() }
}
