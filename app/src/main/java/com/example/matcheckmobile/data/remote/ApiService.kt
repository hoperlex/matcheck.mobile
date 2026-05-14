package com.example.matcheckmobile.data.remote

import com.example.matcheckmobile.data.remote.dto.AttachmentUploadRequest
import com.example.matcheckmobile.data.remote.dto.AttachmentUploadedDto
import com.example.matcheckmobile.data.remote.dto.OperationAcceptedDto
import com.example.matcheckmobile.data.remote.dto.OperationDto

interface ApiService {
    suspend fun sendOperation(operation: OperationDto): Result<OperationAcceptedDto>
    suspend fun uploadAttachment(request: AttachmentUploadRequest): Result<AttachmentUploadedDto>
}
