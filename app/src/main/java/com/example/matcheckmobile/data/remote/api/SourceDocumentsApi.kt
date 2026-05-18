package com.example.matcheckmobile.data.remote.api

import com.example.matcheckmobile.data.remote.api.dto.SourceDocumentDto
import com.example.matcheckmobile.data.remote.api.dto.SourceDocumentFileResponse
import retrofit2.http.GET
import retrofit2.http.Path

interface SourceDocumentsApi {

    @GET("api/v1/source-documents/{id}")
    suspend fun get(@Path("id") id: String): SourceDocumentDto

    @GET("api/v1/source-documents/{id}/file")
    suspend fun file(@Path("id") id: String): SourceDocumentFileResponse
}
