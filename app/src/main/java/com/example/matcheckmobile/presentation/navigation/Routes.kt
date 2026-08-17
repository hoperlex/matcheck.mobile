package com.example.matcheckmobile.presentation.navigation

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val MAIN = "main"
    const val INTAKE_STAGES = "intake_stages"
    const val INTAKE_STAGE2 = "intake_stage2"
    const val INTAKE_UPD_SELECT = "intake_upd_select"
    /**
     * «Ручной внос»: список локальных незавершённых черновиков приёмки
     * (без УПД/автотранспорта) + кнопка «Создать внос». Форма — по draftId;
     * на «Завершить» черновик уходит на сервер как confirmed_mol.
     */
    const val MANUAL_ENTRY_LIST = "manual_entry_list"
    const val SAVED_RECEIPTS = "saved_receipts"
    const val RECEIPT_BASE = "receipt"
    const val ARG_UPD_ID = "updId"
    const val ARG_SESSION_ID = "sessionId"
    const val ARG_DRAFT_ID = "draftId"
    /**
     * «Машина» — id корневого пакета загрузки. Форма 1 Этапа открывается либо по
     * одиночному документу (ARG_UPD_ID), либо по машине целиком (этот аргумент):
     * тогда в приёмку уходят все её документы.
     */
    const val ARG_GROUP_ID = "groupId"

    // Форма ручного вноса — по draftId (константы после ARG_DRAFT_ID:
    // const-инициализация требует, чтобы зависимость была объявлена раньше).
    const val MANUAL_ENTRY_FORM_BASE = "manual_entry_form"
    const val MANUAL_ENTRY_FORM = "$MANUAL_ENTRY_FORM_BASE/{$ARG_DRAFT_ID}"
    fun manualEntryForm(draftId: String): String = "$MANUAL_ENTRY_FORM_BASE/$draftId"

    const val STAGE1_FORM_BASE = "stage1_form"
    const val STAGE1_FORM =
        "$STAGE1_FORM_BASE?$ARG_UPD_ID={$ARG_UPD_ID}&$ARG_DRAFT_ID={$ARG_DRAFT_ID}&$ARG_GROUP_ID={$ARG_GROUP_ID}"
    fun stage1FormForUpd(updId: String): String = "$STAGE1_FORM_BASE?$ARG_UPD_ID=$updId"
    fun stage1FormForGroup(groupId: String): String = "$STAGE1_FORM_BASE?$ARG_GROUP_ID=$groupId"
    fun stage1FormForDraft(draftId: String): String = "$STAGE1_FORM_BASE?$ARG_DRAFT_ID=$draftId"
    fun stage1FormNew(): String = STAGE1_FORM_BASE

    const val ARG_DELIVERY_ID = "deliveryId"
    const val STAGE2_FORM_BASE = "stage2_form"
    const val STAGE2_FORM = "$STAGE2_FORM_BASE/{$ARG_DELIVERY_ID}"
    fun stage2Form(deliveryId: String): String = "$STAGE2_FORM_BASE/$deliveryId"

    /** Архив приёмок (последние 7 дней по локальной БД, без сетевого запроса). */
    const val INTAKE_ARCHIVE = "intake_archive"
    const val INTAKE_ARCHIVE_DETAIL_BASE = "intake_archive_detail"
    const val INTAKE_ARCHIVE_DETAIL = "$INTAKE_ARCHIVE_DETAIL_BASE/{$ARG_DELIVERY_ID}"
    fun intakeArchiveDetail(deliveryId: String): String = "$INTAKE_ARCHIVE_DETAIL_BASE/$deliveryId"

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
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_UPD_ID={$ARG_UPD_ID}&$ARG_DRAFT_ID={$ARG_DRAFT_ID}" +
            "&$ARG_GROUP_ID={$ARG_GROUP_ID}"
    fun dispatchStage1FormForUpd(updId: String): String =
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_UPD_ID=$updId"
    fun dispatchStage1FormForGroup(groupId: String): String =
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_GROUP_ID=$groupId"
    fun dispatchStage1FormForDraft(draftId: String): String =
        "$DISPATCH_STAGE1_FORM_BASE?$ARG_DRAFT_ID=$draftId"
    fun dispatchStage1FormNew(): String = DISPATCH_STAGE1_FORM_BASE

    const val ARG_SHIPMENT_ID = "shipmentId"
    const val DISPATCH_STAGE2_FORM_BASE = "dispatch_stage2_form"
    const val DISPATCH_STAGE2_FORM = "$DISPATCH_STAGE2_FORM_BASE/{$ARG_SHIPMENT_ID}"
    fun dispatchStage2Form(shipmentId: String): String = "$DISPATCH_STAGE2_FORM_BASE/$shipmentId"

    /**
     * «Ручной вынос»: список локальных незавершённых черновиков отгрузки +
     * кнопка «Создать вынос». Форма — по draftId; на «Завершить» уходит на
     * сервер как confirmed_mol (зеркало MANUAL_ENTRY_*).
     */
    const val MANUAL_DISPATCH_LIST = "manual_dispatch_list"
    const val MANUAL_DISPATCH_FORM_BASE = "manual_dispatch_form"
    const val MANUAL_DISPATCH_FORM = "$MANUAL_DISPATCH_FORM_BASE/{$ARG_DRAFT_ID}"
    fun manualDispatchForm(draftId: String): String = "$MANUAL_DISPATCH_FORM_BASE/$draftId"

    /** Архив отгрузок (последние 7 дней, без сетевого запроса). */
    const val DISPATCH_ARCHIVE = "dispatch_archive"
    const val DISPATCH_ARCHIVE_DETAIL_BASE = "dispatch_archive_detail"
    const val DISPATCH_ARCHIVE_DETAIL = "$DISPATCH_ARCHIVE_DETAIL_BASE/{$ARG_SHIPMENT_ID}"
    fun dispatchArchiveDetail(shipmentId: String): String = "$DISPATCH_ARCHIVE_DETAIL_BASE/$shipmentId"
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
