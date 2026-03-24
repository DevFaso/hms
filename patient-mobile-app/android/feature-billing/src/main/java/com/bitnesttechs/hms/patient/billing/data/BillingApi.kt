package com.bitnesttechs.hms.patient.billing.data

import com.bitnesttechs.hms.patient.core.network.PageResponse
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface BillingApi {

    @GET("me/patient/billing/invoices")
    suspend fun getInvoices(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): PageResponse<InvoiceDto>
}

@Serializable
data class InvoiceDto(
    val id: String = "",
    val invoiceNumber: String? = null,
    val invoiceDate: String? = null,
    val dueDate: String? = null,
    val totalAmount: Double? = null,
    val paidAmount: Double? = null,
    val status: String? = null,
    val hospitalName: String? = null,
    val items: List<InvoiceItemDto>? = null
) {
    val balanceDue: Double get() = (totalAmount ?: 0.0) - (paidAmount ?: 0.0)
}

@Serializable
data class InvoiceItemDto(
    val id: String = "",
    val description: String? = null,
    val quantity: Int? = null,
    val unitPrice: Double? = null,
    val totalPrice: Double? = null,
    val serviceDate: String? = null
)
