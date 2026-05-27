package com.example.matcheckmobile.presentation.navigation

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val MAIN = "main"
    const val INTAKE_STAGES = "intake_stages"
    const val INTAKE_STAGE2 = "intake_stage2"
    const val INTAKE_UPD_SELECT = "intake_upd_select"
    const val SAVED_RECEIPTS = "saved_receipts"
    const val RECEIPT_BASE = "receipt"
    const val ARG_UPD_ID = "updId"
    const val ARG_SESSION_ID = "sessionId"
    const val ARG_DRAFT_ID = "draftId"

    const val STAGE1_FORM_BASE = "stage1_form"
    const val STAGE1_FORM = "$STAGE1_FORM_BASE?$ARG_UPD_ID={$ARG_UPD_ID}&$ARG_DRAFT_ID={$ARG_DRAFT_ID}"
    fun stage1FormForUpd(updId: String): String = "$STAGE1_FORM_BASE?$ARG_UPD_ID=$updId"
    fun stage1FormForDraft(draftId: String): String = "$STAGE1_FORM_BASE?$ARG_DRAFT_ID=$draftId"
    fun stage1FormNew(): String = STAGE1_FORM_BASE

    const val ARG_DELIVERY_ID = "deliveryId"
    const val STAGE2_FORM_BASE = "stage2_form"
    const val STAGE2_FORM = "$STAGE2_FORM_BASE/{$ARG_DELIVERY_ID}"
    fun stage2Form(deliveryId: String): String = "$STAGE2_FORM_BASE/$deliveryId"

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
    /** Стартовый экран отгрузки с двумя кнопками 1 Этап / 2 Этап (зеркало INTAKE_STAGES). */
    const val DISPATCH_STAGES = "dispatch_stages"
    const val DISPATCH_UPD_SELECT = "dispatch_upd_select"
    const val DISPATCH_STAGE2 = "dispatch_stage2"

    const val DISPATCH_STAGE1_FORM_BASE = "dispatch_stage1_form"
    const val DISPATCH_STAGE1_FORM =
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_UPD_ID={$ARG_UPD_ID}&$ARG_DRAFT_ID={$ARG_DRAFT_ID}"
    fun dispatchStage1FormForUpd(updId: String): String =
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_UPD_ID=$updId"
    fun dispatchStage1FormForDraft(draftId: String): String =
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_DRAFT_ID=$draftId"
    fun dispatchStage1FormNew(): String = DISPATCH_STAGE1_FORM_BASE

    const val ARG_SHIPMENT_ID = "shipmentId"
    const val DISPATCH_STAGE2_FORM_BASE = "dispatch_stage2_form"
    const val DISPATCH_STAGE2_FORM = "$DISPATCH_STAGE2_FORM_BASE/{$ARG_SHIPMENT_ID}"
    fun dispatchStage2Form(shipmentId: String): String = "$DISPATCH_STAGE2_FORM_BASE/$shipmentId"
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
