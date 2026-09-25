package com.bitnesttechs.hms.patient.core.models

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4 — the wire contract for every patient-facing lab surface is
 * `PatientLabResultResponseDTO`. The JSON below is that DTO's own field
 * spelling; the model used to decode `result`, `collectionDate`,
 * `resultDate` and `labName`, so every one of these assertions failed as a
 * blank.
 */
class LabModelsTest {
    private val moshi = Moshi.Builder().build()

    private fun decodeList(json: String): List<LabResultDto> {
        val listType = Types.newParameterizedType(List::class.java, LabResultDto::class.java)
        val responseType = Types.newParameterizedType(ApiResponse::class.java, listType)
        val adapter = moshi.adapter<ApiResponse<List<LabResultDto>>>(responseType)
        return adapter.fromJson(json)?.data.orEmpty()
    }

    @Test
    fun releasedResultDecodesEveryFieldTheDtoServes() {
        val results = decodeList(
            """
            {
              "success": true,
              "message": "ok",
              "data": [
                {
                  "id": "9c1a1f1e-0000-4000-8000-000000000001",
                  "testName": "Haemoglobin",
                  "testCode": "HGB",
                  "value": "11.2",
                  "unit": "g/dL",
                  "referenceRange": "12 - 16 g/dL",
                  "status": "ABNORMAL_LOW",
                  "released": true,
                  "collectedAt": "2026-09-20T08:30:00",
                  "resultedAt": "2026-09-20T14:05:00",
                  "orderedBy": "Dr Awa Traore",
                  "performedBy": "Moussa Diarra",
                  "category": "HAEMATOLOGY",
                  "notes": "Repeat in two weeks",
                  "hospitalId": "9c1a1f1e-0000-4000-8000-0000000000ff",
                  "hospitalName": "Hopital Gabriel Toure"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, results.size)
        val lab = results.first()
        assertEquals("9c1a1f1e-0000-4000-8000-000000000001", lab.id)
        assertEquals("Haemoglobin", lab.testName)
        assertEquals("HGB", lab.testCode)
        assertEquals("11.2", lab.value)
        assertEquals("g/dL", lab.unit)
        assertEquals("12 - 16 g/dL", lab.referenceRange)
        assertEquals("ABNORMAL_LOW", lab.status)
        assertTrue(lab.released)
        assertEquals("2026-09-20T08:30:00", lab.collectedAt)
        assertEquals("2026-09-20T14:05:00", lab.resultedAt)
        assertEquals("Dr Awa Traore", lab.orderedBy)
        assertEquals("Moussa Diarra", lab.performedBy)
        assertEquals("HAEMATOLOGY", lab.category)
        assertEquals("Repeat in two weeks", lab.notes)
        assertEquals("Hopital Gabriel Toure", lab.hospitalName)

        assertEquals("11.2 g/dL", lab.valueWithUnit)
        assertEquals(LabResultStatus.ABNORMAL_LOW, lab.statusEnum)
        assertFalse(lab.isPending)
        assertTrue(lab.isAbnormal)
        assertFalse(lab.isCritical)
        assertFalse(lab.isNormal)
        assertEquals(StatusTone.ATTENTION, lab.tone)
    }

    /**
     * The patient path redacts an unreleased row: identity and PENDING only.
     * Rendering that as a green "normal" result with a blank value was a real
     * defect on the portal — the model must not let a screen do it here.
     */
    @Test
    fun unreleasedResultReadsAsPendingAndCarriesNoValue() {
        val lab = decodeList(
            """
            {
              "success": true,
              "message": "ok",
              "data": [
                {
                  "id": "9c1a1f1e-0000-4000-8000-000000000002",
                  "testName": "Fasting glucose",
                  "testCode": "GLU",
                  "status": "PENDING",
                  "released": false,
                  "collectedAt": "2026-09-22T07:10:00",
                  "hospitalName": "Hopital Gabriel Toure"
                }
              ]
            }
            """.trimIndent()
        ).first()

        assertTrue(lab.isPending)
        assertNull(lab.value)
        assertNull(lab.valueWithUnit)
        assertNull(lab.referenceRange)
        assertFalse(lab.isNormal)
        assertFalse(lab.isAbnormal)
        assertFalse(lab.isCritical)
        assertEquals(LabResultStatus.PENDING, lab.displayStatus)
        assertEquals(StatusTone.NEUTRAL, lab.tone)
    }

    /**
     * Belt and braces: even if a row arrived unreleased but graded — which is
     * what the STAFF projection does — nothing on a patient screen may show
     * it as a finished result.
     */
    @Test
    fun anUnreleasedRowIsPendingEvenWhenItCarriesAGrading() {
        val lab = LabResultDto(
            id = "x",
            testName = "Potassium",
            value = "6.9",
            unit = "mmol/L",
            status = "CRITICAL",
            released = false
        )

        assertTrue(lab.isPending)
        assertFalse(lab.isCritical)
        assertNull(lab.valueWithUnit)
        assertEquals(LabResultStatus.PENDING, lab.displayStatus)
    }

    /**
     * `statusOf(null)` is NORMAL, so "graded normal" and "nothing graded this"
     * arrive as the same word. Only the first earns the green tick.
     */
    @Test
    fun normalWithoutAReferenceRangeIsNotAnAllClear() {
        val ungraded = LabResultDto(
            id = "z", testName = "Malaria RDT", value = "Positive",
            status = "NORMAL", released = true
        )
        assertTrue(ungraded.isNormal)
        assertFalse(ungraded.isGradedNormal)
        assertEquals(StatusTone.NEUTRAL, ungraded.tone)

        // A range on the test DEFINITION is not proof the value was compared:
        // determineSeverityFlag returns UNSPECIFIED when the value will not
        // parse, and resolveStatus then falls through to NORMAL anyway.
        for (unparsable in listOf("Positive", "<0.5", ">12")) {
            val row = ungraded.copy(value = unparsable, referenceRange = "135 - 145")
            assertFalse("$unparsable must not read as graded", row.isGradedNormal)
            assertEquals(StatusTone.NEUTRAL, row.tone)
        }

        // A decimal comma is a real value to a human, but NOT to
        // Double.parseDouble on the server — so the backend never compared it
        // either, and the app must not treat it as graded.
        val comma = ungraded.copy(value = "4,2", referenceRange = "3.5 - 5.1")
        assertFalse(comma.isGradedNormal)
        assertEquals(StatusTone.NEUTRAL, comma.tone)

        val graded = ungraded.copy(value = "4.2", referenceRange = "3.5 - 5.1")
        assertTrue(graded.isGradedNormal)
        assertEquals(StatusTone.POSITIVE, graded.tone)

        // formatReferenceRange formats ranges[0] while determineSeverityFlag
        // grades against findMatchingRange(unit, …): a row resulted in mmol/L
        // against a first range in mg/dL is an all-clear beside limits it is
        // nowhere near.
        val wrongUnit = ungraded.copy(
            value = "5.4", unit = "mmol/L", referenceRange = "70 - 110 mg/dL"
        )
        assertFalse(wrongUnit.isGradedNormal)
        assertEquals(StatusTone.NEUTRAL, wrongUnit.tone)

        val matchingUnit = wrongUnit.copy(referenceRange = "3.9 - 6.1 mmol/L")
        assertTrue(matchingUnit.isGradedNormal)

        // A substring test would pass all three of these: g/dL is inside
        // mg/dL, mol/L inside mmol/L, U/L inside mU/L.
        val substringTraps = listOf(
            "g/dL" to "70 - 110 mg/dL",
            "mol/L" to "3.9 - 6.1 mmol/L",
            "U/L" to "10 - 40 mU/L"
        )
        for ((rowUnit, shownRange) in substringTraps) {
            val trap = ungraded.copy(value = "5.4", unit = rowUnit, referenceRange = shownRange)
            assertFalse("$rowUnit must not match $shownRange", trap.isGradedNormal)
        }

        // A unit that contains digits still matches itself.
        val digitsInUnit = ungraded.copy(
            value = "7.2", unit = "x10^9/L", referenceRange = "4 - 11 x10^9/L"
        )
        assertTrue(digitsInUnit.isGradedNormal)

        // A range with no unit token where the row HAS one cannot occur —
        // formatReferenceRange falls back to the result's unit — and is not
        // given a pass: only an empty range is.
        val noUnitOnRange = ungraded.copy(value = "5.4", unit = "mmol/L", referenceRange = "3.9 - 6.1")
        assertFalse(noUnitOnRange.isGradedNormal)
    }

    /**
     * Withholding the tick is not enough: the number pair alone tells a
     * patient reading 5.4 mmol/L against "70 - 110 mg/dL" that something is
     * badly wrong.
     */
    @Test
    fun aReferenceRangeInAnotherUnitIsShownWithACaveat() {
        val mismatched = LabResultDto(
            id = "m", testName = "Glucose", value = "5.4", unit = "mmol/L",
            referenceRange = "70 - 110 mg/dL", status = "NORMAL", released = true
        )
        // Shown, not hidden: findMatchingRange falls back to ranges[0], so the
        // displayed range may well BE the graded one and the app cannot tell.
        assertEquals("70 - 110 mg/dL", mismatched.displayReferenceRange)
        assertTrue(mismatched.referenceRangeUnitUncertain)
        // The tick is still withheld, which is the free half of the guard.
        assertFalse(mismatched.isGradedNormal)

        val matched = mismatched.copy(referenceRange = "3.9 - 6.1 mmol/L")
        assertEquals("3.9 - 6.1 mmol/L", matched.displayReferenceRange)
        assertFalse(matched.referenceRangeUnitUncertain)
        assertTrue(matched.isGradedNormal)

        // A pending row has neither: the backend redacts the range, and there
        // is nothing to caveat.
        val pending = mismatched.copy(released = false, status = "PENDING")
        assertNull(pending.displayReferenceRange)
        assertFalse(pending.referenceRangeUnitUncertain)
    }

    /**
     * A unit that ends in a digit — `cells/mm3`, `10^9/L`, `mmol/24h` — must
     * not get an unconditional pass.
     */
    @Test
    fun aUnitEndingInADigitIsStillCompared() {
        val cd4 = LabResultDto(
            id = "c", testName = "CD4 count", value = "0.8", unit = "10^9/L",
            referenceRange = "500 - 1500 cells/mm3", status = "NORMAL", released = true
        )
        assertTrue(cd4.referenceRangeUnitUncertain)
        assertFalse(cd4.isGradedNormal)

        val sameUnit = cd4.copy(referenceRange = "0.5 - 1.5 10^9/L")
        assertFalse(sameUnit.referenceRangeUnitUncertain)
        assertTrue(sameUnit.isGradedNormal)
    }

    /**
     * `findMatchingRange` falls back to `ranges[0]` on any textual
     * disagreement, so the range on screen IS the graded one whenever the
     * difference is only cosmetic. Those must not be caveated.
     */
    @Test
    fun cosmeticUnitDifferencesAreNotTreatedAsAMismatch() {
        val row = LabResultDto(
            id = "u", testName = "Test", value = "100", status = "NORMAL", released = true
        )
        val equivalent = listOf(
            "mmHg" to "90 - 120 mm Hg",
            "umol/L" to "12 - 16 \u00b5mol/L",
            "\u00b5mol/L" to "12 - 16 umol/L",
            "\u03bcmol/L" to "12 - 16 umol/L",
            "10^9/L" to "4 - 11 x10^9/L",
            "x10^9/L" to "4 - 11 10^9/L",
            "G/DL" to "12 - 16 g/dL",
            "ug/dL" to "12 - 16 mcg/dL",
            "mcg/dL" to "12 - 16 \u00b5g/dL",
            "IU/L" to "10 - 40 UI/L",
            "UI/L" to "10 - 40 IU/L"
        )
        for ((rowUnit, shownRange) in equivalent) {
            val lab = row.copy(unit = rowUnit, referenceRange = shownRange)
            assertFalse("$rowUnit vs $shownRange must not be a mismatch", lab.referenceRangeUnitUncertain)
            assertTrue("$rowUnit vs $shownRange must stay graded", lab.isGradedNormal)
        }

        // An SI prefix is never cosmetic: mg and g are a thousandfold apart.
        val prefixed = row.copy(unit = "g/dL", referenceRange = "70 - 110 mg/dL")
        assertTrue(prefixed.referenceRangeUnitUncertain)
        assertFalse(prefixed.isGradedNormal)

        // Nor is a denominator: a row in L against a range in mmol/L is the
        // mislabelling this exists to catch, and "only reject letters" let it
        // through on the `/`.
        val denominator = row.copy(unit = "L", referenceRange = "0.6 - 1.2 mmol/L")
        assertTrue(denominator.referenceRangeUnitUncertain)
        val perKilo = row.copy(unit = "kg", referenceRange = "0 - 2 mg/kg")
        assertTrue(perKilo.referenceRangeUnitUncertain)
    }

    /** A pending row's `resultedAt` is the analyzer's, not the lab's. */
    @Test
    fun aPendingRowShowsTheOrderDateRatherThanAResultDate() {
        val pending = LabResultDto(
            id = "p", testName = "Glucose", status = "PENDING", released = false,
            collectedAt = "2026-09-22T07:10:00", resultedAt = "2026-09-22T14:05:00"
        )
        assertEquals("2026-09-22T07:10:00", pending.displayDate)

        val released = pending.copy(released = true, status = "NORMAL")
        assertEquals("2026-09-22T14:05:00", released.displayDate)
    }

    @Test
    fun unknownOrMissingStatusFallsBackInsteadOfRenderingTheRawName() {
        assertEquals(LabResultStatus.UNKNOWN, LabResultStatus.fromWire(null))
        assertEquals(LabResultStatus.UNKNOWN, LabResultStatus.fromWire(""))
        assertEquals(LabResultStatus.UNKNOWN, LabResultStatus.fromWire("   "))
        assertEquals(LabResultStatus.UNKNOWN, LabResultStatus.fromWire("SOMETHING_NEW"))
        assertEquals(LabResultStatus.CRITICAL, LabResultStatus.fromWire(" critical "))
    }

    /**
     * Every status `PatientLabResultServiceImpl` can put on the wire as of this
     * change, not a sample. Hand-copied, like the prescription one: it pins the
     * app enum against the contract a reader can check by eye, and catches an
     * app-side edit that drops a constant. A status added on the backend
     * reaches the app as `UNKNOWN`, not as a raw `ABNORMAL_HIGH`.
     */
    @Test
    fun everyBackendLabStatusIsNamedByTheEnum() {
        val backendStatuses = setOf(
            "NORMAL", "ABNORMAL", "ABNORMAL_LOW", "ABNORMAL_HIGH", "CRITICAL", "PENDING"
        )
        val named = LabResultStatus.entries
            .filter { it != LabResultStatus.UNKNOWN }
            .map { it.name }
            .toSet()

        assertEquals(backendStatuses, named)
    }

    @Test
    fun everyLabStatusHasItsOwnLabelAndTone() {
        val labels = LabResultStatus.entries.map { it.labelRes }
        assertEquals(LabResultStatus.entries.size, labels.toSet().size)
        assertTrue(labels.none { it == 0 })
        // Exercises the exhaustive `when` for every constant.
        assertTrue(LabResultStatus.entries.all { it.tone in StatusTone.entries })
    }
}
