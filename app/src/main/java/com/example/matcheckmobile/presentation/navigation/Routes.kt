package com.example.matcheckmobile.presentation.navigation

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val RECEIPT = "receipt"
    const val DISPATCH = "dispatch"
    const val JOURNAL = "journal"
    const val SYNC_QUEUE = "sync_queue"
    const val SETTINGS = "settings"

    const val ARG_OPERATION_ID = "operationId"
    const val OPERATION_DETAILS_BASE = "operation_details"
    const val OPERATION_DETAILS = "$OPERATION_DETAILS_BASE/{$ARG_OPERATION_ID}"
    fun operationDetails(operationId: String): String = "$OPERATION_DETAILS_BASE/$operationId"
}
