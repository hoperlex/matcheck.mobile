package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val fullName: String,
    val email: String?,
    val role: String,
    val isActive: Boolean,
    val createdAt: Long,
)
