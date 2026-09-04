package com.example.matcheckmobile.data.local.dao

/**
 * Проекция для уборки: id строки и путь к сохранённой локальной миниатюре.
 * Отдельный класс, а не пара, потому что Room умеет мапить только именованные
 * колонки.
 */
data class LocalThumbRef(
    val id: String,
    val localThumbPath: String?,
)
