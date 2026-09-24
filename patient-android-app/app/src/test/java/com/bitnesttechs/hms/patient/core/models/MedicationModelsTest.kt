package com.bitnesttechs.hms.patient.core.models

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G16 — the prescription list is where a patient reads the pharmacy's
 * decision about their medication. The wire contract is
 * `PrescriptionResponseDTO`, served by `GET /me/patient/prescriptions`.
 */
class MedicationModelsTest {
    private val moshi = Moshi.Builder().build()

    private fun decode(json: String): PrescriptionDto {
        val listType = Types.newParameterizedType(List::class.java, PrescriptionDto::class.java)
        val responseType = Types.newParameterizedType(ApiResponse::class.java, listType)
        val adapter = moshi.adapter<ApiResponse<List<PrescriptionDto>>>(responseType)
        return adapter.fromJson(json)?.data.orEmpty().first()
    }

    @Test
    fun prescriptionDecodesTheNamesTheDtoActuallyServes() {
        val rx = decode(
            """
            {
              "success": true,
              "message": "ok",
              "data": [
                {
                  "id": "3f0a1c22-0000-4000-8000-000000000001",
                  "medicationName": "Amoxicillin",
                  "medicationDisplayName": "Amoxicillin 500 mg capsule",
                  "dosage": "500 mg",
                  "frequency": "Three times a day",
                  "duration": "7 days",
                  "route": "Oral",
                  "status": "PENDING_STOCK",
                  "staffFullName": "Dr Awa Traore",
                  "createdAt": "2026-09-18T09:00:00",
                  "pharmacyName": "Pharmacie du Fleuve",
                  "instructions": "Take with food"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("Amoxicillin", rx.medicationName)
        assertEquals("Amoxicillin 500 mg capsule", rx.displayName)
        assertEquals("7 days", rx.duration)
        assertEquals("Oral", rx.route)
        assertEquals("Dr Awa Traore", rx.staffFullName)
        assertEquals("2026-09-18T09:00:00", rx.createdAt)
        assertEquals("Pharmacie du Fleuve", rx.pharmacyName)
        assertEquals(PrescriptionStatus.PENDING_STOCK, rx.statusEnum)
        assertEquals(StatusTone.ATTENTION, rx.statusEnum.tone)
    }

    @Test
    fun displayNameFallsBackToTheRawMedicationName() {
        val rx = PrescriptionDto(medicationName = "Paracetamol")
        assertEquals("Paracetamol", rx.displayName)
    }

    @Test
    fun unknownOrMissingStatusFallsBackInsteadOfRenderingTheRawName() {
        assertEquals(PrescriptionStatus.UNKNOWN, PrescriptionStatus.fromWire(null))
        assertEquals(PrescriptionStatus.UNKNOWN, PrescriptionStatus.fromWire(""))
        assertEquals(PrescriptionStatus.UNKNOWN, PrescriptionStatus.fromWire("BRAND_NEW_STATE"))
        assertEquals(
            PrescriptionStatus.PARTNER_REJECTED,
            PrescriptionStatus.fromWire(" partner_rejected ")
        )
    }

    /**
     * Every constant of `com.example.hms.enums.PrescriptionStatus`, pinned
     * here so a backend addition shows up as a failure on this side instead
     * of as a raw wire name on a patient's screen.
     */
    @Test
    fun theEnumCoversEveryBackendPrescriptionStatus() {
        val backend = setOf(
            "DRAFT", "PENDING_SIGNATURE", "SIGNED", "TRANSMITTED", "TRANSMISSION_FAILED",
            "CANCELLED", "DISCONTINUED", "PENDING_CLARIFICATION", "DISPENSED",
            "PARTIALLY_FILLED", "PENDING_STOCK", "REQUIRES_EXTERNAL_FILL", "SENT_TO_PARTNER",
            "PARTNER_ACCEPTED", "PARTNER_REJECTED", "PARTNER_DISPENSED", "PRINTED_FOR_PATIENT"
        )
        val named = PrescriptionStatus.entries
            .filter { it != PrescriptionStatus.UNKNOWN }
            .map { it.name }
            .toSet()

        assertEquals(backend, named)
    }

    @Test
    fun theEnumCoversEveryBackendRefillStatus() {
        val backend = setOf("REQUESTED", "PAUSED", "APPROVED", "DENIED", "DISPENSED", "CANCELLED")
        val named = RefillStatus.entries
            .filter { it != RefillStatus.UNKNOWN }
            .map { it.name }
            .toSet()

        assertEquals(backend, named)
    }

    /**
     * Mirrors `PrescriptionStatus.isRefillable()`. The prescriptions tab used
     * to gate its Request-refill button on a `refillsRemaining` counter the
     * DTO has never carried, so the button never appeared at all.
     */
    @Test
    fun refillabilityMatchesTheBackendRule() {
        val notRefillable = setOf(
            PrescriptionStatus.DRAFT,
            PrescriptionStatus.PENDING_SIGNATURE,
            PrescriptionStatus.CANCELLED,
            PrescriptionStatus.DISCONTINUED
        )
        for (status in PrescriptionStatus.entries) {
            assertEquals(
                "refillability of $status",
                status !in notRefillable,
                status.isRefillable
            )
        }
    }

    @Test
    fun onlyRequestedAndPausedRefillsCanBeWithdrawn() {
        val cancellable = setOf(RefillStatus.REQUESTED, RefillStatus.PAUSED)
        for (status in RefillStatus.entries) {
            assertEquals("cancellability of $status", status in cancellable, status.isCancellable)
        }
        assertTrue(RefillDto(status = "PAUSED").statusEnum.isCancellable)
        assertFalse(RefillDto(status = "DISPENSED").statusEnum.isCancellable)
    }

    /**
     * `OPEN_REFILL_STATUSES` in `PatientPortalServiceImpl` — the set that
     * makes a second request for the same prescription a 400.
     */
    @Test
    fun onlyRequestedAndPausedRefillsBlockANewRequest() {
        val open = setOf(RefillStatus.REQUESTED, RefillStatus.PAUSED)
        for (status in RefillStatus.entries) {
            assertEquals("openness of $status", status in open, status.isOpen)
        }
    }

    @Test
    fun everyPrescriptionAndRefillStatusHasItsOwnLabel() {
        val rxLabels = PrescriptionStatus.entries.map { it.labelRes }
        assertEquals(PrescriptionStatus.entries.size, rxLabels.toSet().size)
        assertTrue(rxLabels.none { it == 0 })

        val refillLabels = RefillStatus.entries.map { it.labelRes }
        assertEquals(RefillStatus.entries.size, refillLabels.toSet().size)
        assertTrue(refillLabels.none { it == 0 })
    }
}
