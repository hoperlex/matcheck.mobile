package com.example.matcheckmobile.presentation.navigation

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val INTAKE_STAGES = "intake_stages"
    const val INTAKE_STAGE2 = "intake_stage2"
    const val INTAKE_UPD_SELECT = "intake_upd_select"
    const val SAVED_RECEIPTS = "saved_receipts"
    const val RECEIPT_BASE = "receipt"
    const val ARG_UPD_ID = "updId"
    const val ARG_SESSION_ID = "sessionId"

    const val STAGE1_FORM_BASE = "stage1_form"
    const val STAGE1_FORM = "$STAGE1_FORM_BASE?$ARG_UPD_ID={$ARG_UPD_ID}"
    fun stage1FormForUpd(updId: String): String = "$STAGE1_FORM_BASE?$ARG_UPD_ID=$updId"
    fun stage1FormNew(): String = STAGE1_FORM_BASE

    /**
     * `receipt?updId=...&sessionId=...` — оба опциональны.
     * `updId` — для новой приёмки с предвыбранным УПД;
     * `sessionId` — для продолжения сохранённой приёмки.
     */
    const val RECEIPT = "$RECEIPT_BASE?$ARG_UPD_ID={$ARG_UPD_ID}&$ARG_SESSION_ID={$ARG_SESSION_ID}"

    fun receiptForUpd(updId: String): String = "$RECEIPT_BASE?$ARG_UPD_ID=$updId"
    fun receiptForSession(sessionId: String): String = "$RECEIPT_BASE?$ARG_SESSION_ID=$sessionId"
    fun receiptNew(): String = RECEIPT_BASE

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
