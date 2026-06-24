package com.example.matcheckmobile.data.local.dao

/**
 * Лёгкая проекция (id, version) локальной записи для reconcile-сверки.
 * Только чтение — DAO выбирают её SELECT'ом, чтобы не тянуть полные сущности.
 */
data class ReconcileVersionRow(
    val id: String,
    val version: Int,
)
