package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "counterparties")
data class CounterpartyEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val inn: String,
    val kpp: String?,
    val name: String,
    val address: String?,
    val isSupplier: Boolean,
    val isCustomer: Boolean,
    val isCarrier: Boolean,
    val isContractor: Boolean,
    val updatedAt: Long,
)
