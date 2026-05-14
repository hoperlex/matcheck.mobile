package com.example.matcheckmobile.data.local

import androidx.room.TypeConverter
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.domain.model.SyncStatus
import com.example.matcheckmobile.domain.model.UploadStatus

class Converters {
    @TypeConverter fun operationTypeToString(value: OperationType): String = value.name
    @TypeConverter fun stringToOperationType(value: String): OperationType = OperationType.valueOf(value)

    @TypeConverter fun syncStatusToString(value: SyncStatus): String = value.name
    @TypeConverter fun stringToSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter fun uploadStatusToString(value: UploadStatus): String = value.name
    @TypeConverter fun stringToUploadStatus(value: String): UploadStatus = UploadStatus.valueOf(value)

    @TypeConverter fun attachmentTypeToString(value: AttachmentType): String = value.name
    @TypeConverter fun stringToAttachmentType(value: String): AttachmentType = AttachmentType.valueOf(value)
}
