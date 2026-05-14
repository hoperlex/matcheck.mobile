package com.example.matcheckmobile.data.remote

import com.example.matcheckmobile.data.remote.dto.AttachmentUploadRequest
import com.example.matcheckmobile.data.remote.dto.AttachmentUploadedDto
import com.example.matcheckmobile.data.remote.dto.OperationAcceptedDto
import com.example.matcheckmobile.data.remote.dto.OperationDto
import com.example.matcheckmobile.data.remote.dto.SessionAcceptedDto
import com.example.matcheckmobile.data.remote.dto.SessionDto

interface ApiService {
    suspend fun sendOperation(operation: OperationDto): Result<OperationAcceptedDto>
    suspend fun sendSession(session: SessionDto): Result<SessionAcceptedDto>
    suspend fun uploadAttachment(request: AttachmentUploadRequest): Result<AttachmentUploadedDto>
}
