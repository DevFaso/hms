package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class InvoiceDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "invoiceNumber") val invoiceNumber: String = "",
    @Json(name = "invoiceDate") val invoiceDate: String? = null,
    @Json(name = "dueDate") val dueDate: String? = null,
    @Json(name = "totalAmount") val totalAmount: Double = 0.0,
    @Json(name = "paidAmount") val paidAmount: Double = 0.0,
    @Json(name = "balanceDue") val balanceDue: Double = 0.0,
    @Json(name = "status") val status: String = "",
    @Json(name = "description") val description: String? = null,
    @Json(name = "items") val items: List<InvoiceItemDto>? = null
) {
    val isPaid: Boolean get() = status.uppercase() == "PAID"
    val isCancelled: Boolean get() = status.uppercase() == "CANCELLED"
    val statusDisplay: String get() = status.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
}

@JsonClass(generateAdapter = true)
data class InvoiceItemDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "description") val description: String = "",
    @Json(name = "quantity") val quantity: Int = 1,
    @Json(name = "unitPrice") val unitPrice: Double = 0.0,
    @Json(name = "totalPrice") val totalPrice: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class PharmacyPaymentDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "dispenseId") val dispenseId: String = "",
    @Json(name = "patientId") val patientId: String = "",
    @Json(name = "hospitalId") val hospitalId: String = "",
    @Json(name = "paymentMethod") val paymentMethod: String = "",
    @Json(name = "amount") val amount: Double = 0.0,
    @Json(name = "currency") val currency: String = "XOF",
    @Json(name = "referenceNumber") val referenceNumber: String? = null,
    @Json(name = "receivedBy") val receivedBy: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "createdAt") val createdAt: String = "",
    @Json(name = "updatedAt") val updatedAt: String? = null
) {
    val methodDisplay: String get() = paymentMethod.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
}

@JsonClass(generateAdapter = true)
data class PharmacyClaimDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "dispenseId") val dispenseId: String = "",
    @Json(name = "patientId") val patientId: String = "",
    @Json(name = "hospitalId") val hospitalId: String = "",
    @Json(name = "coverageReference") val coverageReference: String? = null,
    @Json(name = "claimStatus") val claimStatus: String = "",
    @Json(name = "amount") val amount: Double = 0.0,
    @Json(name = "currency") val currency: String = "XOF",
    @Json(name = "submittedAt") val submittedAt: String? = null,
    @Json(name = "submittedBy") val submittedBy: String? = null,
    @Json(name = "rejectionReason") val rejectionReason: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "createdAt") val createdAt: String = "",
    @Json(name = "updatedAt") val updatedAt: String? = null
) {
    val statusDisplay: String get() = claimStatus.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
}
