package com.bitnesttechs.hms.patient.core.models

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MedicationDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "medicationName") val medicationName: String = "",
    @Json(name = "dosage") val dosage: String? = null,
    @Json(name = "frequency") val frequency: String? = null,
    @Json(name = "route") val route: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null,
    @Json(name = "prescribedBy") val prescribedBy: String? = null,
    @Json(name = "instructions") val instructions: String? = null,
    @Json(name = "isActive") val isActive: Boolean = true,
    /**
     * `PatientMedicationResponseDTO` is built one-to-one FROM prescriptions
     * and its `id` IS the prescription id, so these two answer the refill
     * question for a prescription authoritatively: `refillRequestOpen` is
     * computed over every refill row for the page (`latestRefillsFor`, no
     * pagination), unlike anything the app can derive from a page of
     * `/me/patient/refills`.
     */
    @Json(name = "refillable") val refillable: Boolean = true,
    @Json(name = "refillRequestOpen") val refillRequestOpen: Boolean = false,
    /**
     * The wire name of that request's status, so the app can tell "awaiting
     * review" from "your care team put it on hold" without a second call.
     */
    @Json(name = "refillRequestStatus") val refillRequestStatus: String? = null
) {
    /** Backward compat alias */
    val name: String get() = medicationName

    /**
     * The state of the open refill on this prescription, or null when there
     * is none. `refillRequestStatus` can name a CLOSED state (the newest
     * request, whatever it was), so `refillRequestOpen` decides and the
     * status only chooses the wording.
     */
    val openRefillStatus: RefillStatus?
        get() {
            if (!refillRequestOpen) return null
            val status = RefillStatus.fromWire(refillRequestStatus)
            return if (status.isOpen) status else RefillStatus.REQUESTED
        }
}

/**
 * Wire contract: `PrescriptionResponseDTO`
 * (`hospital-core/.../payload/dto/PrescriptionResponseDTO.java`), served by
 * `GET /me/patient/prescriptions` through
 * `PatientPortalServiceImpl.getMyPrescriptions`, which strips the
 * pharmacist-to-prescriber clarification exchange and leaves everything else.
 *
 * `quantity`, `expiryDate` and `refillsRemaining` used to be mapped here and
 * are not on THIS DTO. The counter does exist on the domain — `Prescription`
 * has `refillsAllowed`/`refillsRemaining`/`refillsUsed`, and
 * `PatientMedicationResponseDTO` serves all three — but
 * `PrescriptionResponseDTO` omits them, so the prescriptions tab cannot read
 * one. Whether a refill may be requested is decided by
 * [PrescriptionStatus.isRefillable], the same rule
 * `PrescriptionStatus.isRefillable()` applies server-side. `prescribedBy` and
 * `prescribedDate` are served, under the names `staffFullName` and
 * `createdAt`.
 */
@JsonClass(generateAdapter = true)
data class PrescriptionDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "medicationName") val medicationName: String = "",
    @Json(name = "medicationDisplayName") val medicationDisplayName: String? = null,
    @Json(name = "dosage") val dosage: String? = null,
    @Json(name = "frequency") val frequency: String? = null,
    @Json(name = "duration") val duration: String? = null,
    @Json(name = "route") val route: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "staffFullName") val staffFullName: String? = null,
    @Json(name = "createdAt") val createdAt: String? = null,
    /**
     * Where the prescription went: the partner pharmacy it was routed to, or
     * the community pharmacy it was dispatched to by SMS. Null while the
     * order is still at — or was filled by — the hospital's own dispensary.
     */
    @Json(name = "pharmacyName") val pharmacyName: String? = null,
    @Json(name = "instructions") val instructions: String? = null
) {
    val displayName: String
        get() = medicationDisplayName?.takeIf { it.isNotBlank() } ?: medicationName

    val statusEnum: PrescriptionStatus get() = PrescriptionStatus.fromWire(status)
}

/**
 * Mirrors `com.example.hms.enums.PrescriptionStatus`. Every `when` is
 * exhaustive and carries no `else`, so adding a constant here without a label
 * fails the build rather than falling back to the raw wire name — which is
 * how a patient came to read `PENDING_STOCK` and `PARTNER_REJECTED` on their
 * own prescription list (gap G16).
 */
enum class PrescriptionStatus {
    DRAFT,
    PENDING_SIGNATURE,
    SIGNED,
    TRANSMITTED,
    TRANSMISSION_FAILED,
    CANCELLED,
    DISCONTINUED,
    PENDING_CLARIFICATION,
    DISPENSED,
    PARTIALLY_FILLED,
    PENDING_STOCK,
    REQUIRES_EXTERNAL_FILL,
    SENT_TO_PARTNER,
    PARTNER_ACCEPTED,
    PARTNER_REJECTED,
    PARTNER_DISPENSED,
    PRINTED_FOR_PATIENT,

    /** App-side fallback for a status this build does not know yet. */
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            DRAFT -> R.string.rx_status_draft
            PENDING_SIGNATURE -> R.string.rx_status_pending_signature
            SIGNED -> R.string.rx_status_signed
            TRANSMITTED -> R.string.rx_status_transmitted
            TRANSMISSION_FAILED -> R.string.rx_status_transmission_failed
            CANCELLED -> R.string.rx_status_cancelled
            DISCONTINUED -> R.string.rx_status_discontinued
            PENDING_CLARIFICATION -> R.string.rx_status_pending_clarification
            DISPENSED -> R.string.rx_status_dispensed
            PARTIALLY_FILLED -> R.string.rx_status_partially_filled
            PENDING_STOCK -> R.string.rx_status_pending_stock
            REQUIRES_EXTERNAL_FILL -> R.string.rx_status_requires_external_fill
            SENT_TO_PARTNER -> R.string.rx_status_sent_to_partner
            PARTNER_ACCEPTED -> R.string.rx_status_partner_accepted
            PARTNER_REJECTED -> R.string.rx_status_partner_rejected
            PARTNER_DISPENSED -> R.string.rx_status_partner_dispensed
            PRINTED_FOR_PATIENT -> R.string.rx_status_printed_for_patient
            UNKNOWN -> R.string.rx_status_unknown
        }

    val tone: StatusTone
        get() = when (this) {
            DISPENSED, PARTNER_DISPENSED, PARTNER_ACCEPTED -> StatusTone.POSITIVE
            PENDING_STOCK, PARTIALLY_FILLED, PENDING_CLARIFICATION, PENDING_SIGNATURE ->
                StatusTone.ATTENTION
            PARTNER_REJECTED, TRANSMISSION_FAILED, CANCELLED, DISCONTINUED -> StatusTone.NEGATIVE
            DRAFT, SIGNED, TRANSMITTED, SENT_TO_PARTNER, REQUIRES_EXTERNAL_FILL,
            PRINTED_FOR_PATIENT, UNKNOWN -> StatusTone.NEUTRAL
        }

    /**
     * The same rule as `PrescriptionStatus.isRefillable()` server-side: a
     * patient asks for a refill precisely because the medication was already
     * dispensed, so only a prescription that was never signed or has been
     * withdrawn is un-refillable. An unrecognised status is treated as
     * refillable, exactly as the backend's `default ->` branch does; the
     * request endpoint re-checks and is the real gate.
     */
    val isRefillable: Boolean
        get() = when (this) {
            DRAFT, PENDING_SIGNATURE, CANCELLED, DISCONTINUED -> false
            SIGNED, TRANSMITTED, TRANSMISSION_FAILED, PENDING_CLARIFICATION, DISPENSED,
            PARTIALLY_FILLED, PENDING_STOCK, REQUIRES_EXTERNAL_FILL, SENT_TO_PARTNER,
            PARTNER_ACCEPTED, PARTNER_REJECTED, PARTNER_DISPENSED, PRINTED_FOR_PATIENT,
            UNKNOWN -> true
        }

    companion object {
        fun fromWire(raw: String?): PrescriptionStatus {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

@JsonClass(generateAdapter = true)
data class RefillDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "prescriptionId") val prescriptionId: String? = null,
    @Json(name = "medicationName") val medicationName: String? = null,
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "preferredPharmacy") val preferredPharmacy: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "providerNotes") val providerNotes: String? = null,
    @Json(name = "requestedAt") val requestedAt: String? = null,
    @Json(name = "updatedAt") val updatedAt: String? = null
) {
    val statusEnum: RefillStatus get() = RefillStatus.fromWire(status)
}

/** Mirrors `com.example.hms.enums.RefillStatus`. */
enum class RefillStatus {
    REQUESTED,
    PAUSED,
    APPROVED,
    DENIED,
    DISPENSED,
    CANCELLED,

    /** App-side fallback for a status this build does not know yet. */
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            REQUESTED -> R.string.refill_status_requested
            PAUSED -> R.string.refill_status_paused
            APPROVED -> R.string.refill_status_approved
            DENIED -> R.string.refill_status_denied
            DISPENSED -> R.string.refill_status_dispensed
            CANCELLED -> R.string.refill_status_cancelled
            UNKNOWN -> R.string.refill_status_unknown
        }

    val tone: StatusTone
        get() = when (this) {
            APPROVED, DISPENSED -> StatusTone.POSITIVE
            REQUESTED, PAUSED -> StatusTone.ATTENTION
            DENIED -> StatusTone.NEGATIVE
            CANCELLED, UNKNOWN -> StatusTone.NEUTRAL
        }

    /**
     * Still with the provider, so a second request for the same
     * prescription would be refused: `OPEN_REFILL_STATUSES` in
     * `PatientPortalServiceImpl`. Deliberately a separate rule from
     * [isCancellable] even though the two sets coincide today — one is
     * about what the patient may withdraw, the other about what blocks a
     * new request, and they are free to diverge.
     */
    val isOpen: Boolean
        get() = when (this) {
            REQUESTED, PAUSED -> true
            APPROVED, DENIED, DISPENSED, CANCELLED, UNKNOWN -> false
        }

    /**
     * REQUESTED and PAUSED are the two states `cancelMyRefill` lets the
     * patient withdraw.
     */
    val isCancellable: Boolean
        get() = when (this) {
            REQUESTED, PAUSED -> true
            APPROVED, DENIED, DISPENSED, CANCELLED, UNKNOWN -> false
        }

    companion object {
        fun fromWire(raw: String?): RefillStatus {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

@JsonClass(generateAdapter = true)
data class RefillRequest(
    @Json(name = "prescriptionId") val prescriptionId: String,
    @Json(name = "preferredPharmacy") val preferredPharmacy: String? = null,
    @Json(name = "notes") val notes: String? = null
)
