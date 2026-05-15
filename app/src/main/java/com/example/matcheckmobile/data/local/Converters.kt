package com.example.matcheckmobile.data.local

import androidx.room.TypeConverter
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.domain.model.SessionKind
import com.example.matcheckmobile.domain.model.SourceKind
import com.example.matcheckmobile.domain.model.SourceOrigin
import com.example.matcheckmobile.domain.model.SourceStatus
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

    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name
    @TypeConverter fun stringToSourceKind(value: String): SourceKind = SourceKind.valueOf(value)

    @TypeConverter fun sourceStatusToString(value: SourceStatus): String = value.name
    @TypeConverter fun stringToSourceStatus(value: String): SourceStatus = SourceStatus.valueOf(value)

    @TypeConverter fun sourceOriginToString(value: SourceOrigin): String = value.name
    @TypeConverter fun stringToSourceOrigin(value: String): SourceOrigin = SourceOrigin.valueOf(value)

    @TypeConverter fun sessionKindToString(value: SessionKind): String = value.name
    @TypeConverter fun stringToSessionKind(value: String): SessionKind = SessionKind.valueOf(value)
}
