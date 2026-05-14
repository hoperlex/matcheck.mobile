package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.CounterpartyDao
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import kotlinx.coroutines.flow.Flow

class CounterpartyRepository(
    private val dao: CounterpartyDao,
) {
    fun observeSuppliers(): Flow<List<CounterpartyEntity>> = dao.observeSuppliers()

    fun observeAll(): Flow<List<CounterpartyEntity>> = dao.observeAll()

    suspend fun findById(id: String): CounterpartyEntity? = dao.findById(id)
}
