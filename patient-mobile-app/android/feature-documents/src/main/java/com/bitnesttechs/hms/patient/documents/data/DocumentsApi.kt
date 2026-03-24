package com.bitnesttechs.hms.patient.documents.data

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.bitnesttechs.hms.patient.core.network.PageResponse
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface DocumentsApi {
    @GET("me/patient/documents")
    suspend fun getDocuments(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): ApiResponse<PageResponse<DocumentDto>>
}

@Serializable
data class DocumentDto(
    val id: String,
    val documentName: String? = null,
    val documentType: String? = null,
    val category: String? = null,
    val description: String? = null,
    val fileName: String? = null,
    val fileSize: Int? = null,
    val mimeType: String? = null,
    val uploadedBy: String? = null,
    val uploadedByRole: String? = null,
    val hospitalName: String? = null,
    val departmentName: String? = null,
    val status: String? = null,
    val isConfidential: Boolean? = null,
    val uploadDate: String? = null,
    val createdAt: String? = null
)
