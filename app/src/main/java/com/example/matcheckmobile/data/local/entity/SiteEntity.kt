package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val name: String,
    val address: String?,
    val createdAt: Long,
)
