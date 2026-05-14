package com.example.matcheckmobile.data.remote.dto

data class AttachmentUploadRequest(
    val operationServerId: String,
    val attachmentLocalId: String,
    val attachmentType: String,
    val localFilePath: String,
)

data class AttachmentUploadedDto(
    val remoteUrl: String,
)
