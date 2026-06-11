package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CounterpartyDto(
    val id: String,
    val inn: String,
    val kpp: String? = null,
    val name: String,
    val address: String? = null,
    val isSelf: Boolean = false,
    val isSupplier: Boolean = false,
    val isCustomer: Boolean = false,
    val isContractor: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MaterialDto(
    val id: String,
    val code: String? = null,
    val name: String,
    val unit: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class SiteDto(
    val id: String,
    val code: String,
    val name: String,
    val fullName: String? = null,
    val address: String? = null,
    val isActive: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class StatusDto(
    val id: String,
    val entityType: String,
    val code: String,
    val label: String,
    val color: String? = null,
    val sortOrder: Int,
)

/**
 * Единица измерения из серверного справочника. Возвращается /sync в
 * массиве `units` для дропдауна «Ед.» в модалке материалов на мобиле.
 * okeiCode nullable: для пользовательских единиц («бухта», «мешок»)
 * общероссийского кода может не быть.
 */
@Serializable
data class UnitDto(
    val id: String,
    val code: String,
    val name: String,
    val okeiCode: String? = null,
    val isActive: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
)
