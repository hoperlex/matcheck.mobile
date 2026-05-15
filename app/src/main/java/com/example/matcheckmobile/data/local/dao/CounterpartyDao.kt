package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CounterpartyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(counterparty: CounterpartyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CounterpartyEntity>)

    @Query("SELECT * FROM counterparties WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): CounterpartyEntity?

    @Query("SELECT * FROM counterparties ORDER BY name")
    fun observeAll(): Flow<List<CounterpartyEntity>>

    @Query("SELECT * FROM counterparties WHERE isSupplier = 1 ORDER BY name")
    fun observeSuppliers(): Flow<List<CounterpartyEntity>>

    @Query("SELECT * FROM counterparties WHERE isContractor = 1 ORDER BY name")
    fun observeContractors(): Flow<List<CounterpartyEntity>>

    @Query("SELECT COUNT(*) FROM counterparties")
    suspend fun count(): Int
}
