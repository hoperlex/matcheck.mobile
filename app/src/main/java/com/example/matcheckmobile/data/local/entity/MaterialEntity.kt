package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "materials")
data class MaterialEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val code: String?,
    val name: String,
    val unit: String,
    val updatedAt: Long,
)
