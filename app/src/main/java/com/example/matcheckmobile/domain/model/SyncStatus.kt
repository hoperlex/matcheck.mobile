package com.example.matcheckmobile.domain.model

enum class SyncStatus {
    DRAFT,
    LOCAL_SAVED,
    COMPLETED,
    PENDING,
    SYNCING,
    SYNCED,
    ERROR,
    NEEDS_REVIEW,
}
