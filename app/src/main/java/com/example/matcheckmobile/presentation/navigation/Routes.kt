package com.example.matcheckmobile.presentation.navigation

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val RECEIPT = "receipt"
    const val DISPATCH = "dispatch"
    const val JOURNAL = "journal"
    const val SYNC_QUEUE = "sync_queue"
    const val SETTINGS = "settings"
    const val DOCUMENTS = "documents"

    const val ARG_OPERATION_ID = "operationId"
    const val OPERATION_DETAILS_BASE = "operation_details"
    const val OPERATION_DETAILS = "$OPERATION_DETAILS_BASE/{$ARG_OPERATION_ID}"
    fun operationDetails(operationId: String): String = "$OPERATION_DETAILS_BASE/$operationId"

    const val ARG_DOCUMENT_ID = "documentId"
    const val DOCUMENT_DETAILS_BASE = "document_details"
    const val DOCUMENT_DETAILS = "$DOCUMENT_DETAILS_BASE/{$ARG_DOCUMENT_ID}"
    fun documentDetails(documentId: String): String = "$DOCUMENT_DETAILS_BASE/$documentId"
}
