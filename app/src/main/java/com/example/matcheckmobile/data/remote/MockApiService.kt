package com.example.matcheckmobile.data.remote

import com.example.matcheckmobile.data.remote.dto.AttachmentUploadRequest
import com.example.matcheckmobile.data.remote.dto.AttachmentUploadedDto
import com.example.matcheckmobile.data.remote.dto.OperationAcceptedDto
import com.example.matcheckmobile.data.remote.dto.OperationDto
import kotlinx.coroutines.delay
import java.util.UUID

class MockApiService : ApiService {
    override suspend fun sendOperation(operation: OperationDto): Result<OperationAcceptedDto> {
        delay(400)
        return Result.success(
            OperationAcceptedDto(
                serverId = "srv-" + UUID.nameUUIDFromBytes(operation.idempotencyKey.toByteArray()),
                receivedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun uploadAttachment(request: AttachmentUploadRequest): Result<AttachmentUploadedDto> {
        delay(600)
        return Result.success(
            AttachmentUploadedDto(
                remoteUrl = "https://mock.matcheck.local/uploads/${request.attachmentLocalId}.jpg",
            )
        )
    }
}
