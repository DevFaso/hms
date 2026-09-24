package com.bitnesttechs.hms.patient.core.models

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * B4 — the wire contract is `PatientLabResultResponseDTO`
 * (`hospital-core/.../payload/dto/lab/PatientLabResultResponseDTO.java`),
 * served by every patient-facing lab surface:
 *
 *  * `GET /me/patient/lab-results`
 *  * `GET /me/patient/proxy-access/{patientId}/lab-results`
 *  * `HealthSummaryDTO.recentLabResults` (dashboard / health records)
 *
 * The previous model decoded `result`, `collectionDate`, `resultDate` and
 * `labName` — four names the API has never sent — so every value, every date
 * and the laboratory were blank on every row. Field names below are the DTO's
 * own; do not rename one without changing the DTO.
 *
 * `hospitalId` is deliberately not mapped: it is the provenance UUID the
 * portal uses for cross-hospital accounting and there is nothing a patient
 * screen can render from it. `hospitalName` is the display form.
 */
@JsonClass(generateAdapter = true)
data class LabResultDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "testName") val testName: String = "",
    @Json(name = "testCode") val testCode: String? = null,
    @Json(name = "value") val value: String? = null,
    @Json(name = "unit") val unit: String? = null,
    @Json(name = "referenceRange") val referenceRange: String? = null,
    @Json(name = "status") val status: String = "",
    /**
     * Whether the laboratory has released the result. The patient path
     * redacts an unreleased row down to its identity, so this is what tells
     * a blank value apart from a result that genuinely has none.
     */
    @Json(name = "released") val released: Boolean = false,
    @Json(name = "collectedAt") val collectedAt: String? = null,
    @Json(name = "resultedAt") val resultedAt: String? = null,
    @Json(name = "orderedBy") val orderedBy: String? = null,
    @Json(name = "performedBy") val performedBy: String? = null,
    /**
     * Decoded but not displayed: LabTestDefinition normalises this to
     * trim().toUpperCase() and the set is hospital-configured, so there is no
     * closed list to localize it against.
     */
    @Json(name = "category") val category: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null
) {
    val statusEnum: LabResultStatus get() = LabResultStatus.fromWire(status)

    /**
     * An unreleased row, or one the backend marked PENDING. It carries no
     * value, no unit, no reference range and no interpretation: rendering it
     * as a green "normal" result with a blank value was a real defect on the
     * patient portal and must not be reproduced here.
     */
    val isPending: Boolean get() = !released || statusEnum == LabResultStatus.PENDING

    val isCritical: Boolean get() = !isPending && statusEnum == LabResultStatus.CRITICAL

    val isAbnormal: Boolean get() = !isPending && statusEnum.isAbnormal

    val isNormal: Boolean get() = !isPending && statusEnum == LabResultStatus.NORMAL

    /**
     * NORMAL *with a range it could have been inside*. `resolveStatus` falls
     * through to `statusOf(result.getAbnormalFlag())` and `statusOf(null)` is
     * also NORMAL, so on the wire "graded normal" and "nothing graded this"
     * are the same word. The green tick and the green badge are the app's own
     * reassurance rather than anything the backend asserted, so they are
     * withheld when there was no range to be inside — a positive qualitative
     * serology must not read as an all-clear.
     */
    val isGradedNormal: Boolean
        get() {
            if (!isNormal || referenceRange.isNullOrBlank()) return false
            // A range alone is not proof it was applied: the range comes off
            // the test DEFINITION, while `LabResultMapper.determineSeverityFlag`
            // returns UNSPECIFIED whenever `Double.parseDouble(resultValue)`
            // throws — a censored "<0.5", a qualitative "Positive", or a
            // DECIMAL COMMA, on a test that happens to have numeric limits.
            // `toDoubleOrNull` is deliberately not given the comma: the
            // question is not whether the value is a number to a human, it is
            // whether the SERVER could parse it, and `Double.parseDouble`
            // cannot. Normalising here would hand a francophone site's "4,2"
            // a green tick for a comparison that never happened.
            val raw = value?.trim().orEmpty()
            return raw.isNotEmpty() && raw.toDoubleOrNull() != null
        }

    /** The value with its unit, or null while the result is pending. */
    val valueWithUnit: String?
        get() {
            if (isPending) return null
            val raw = value?.takeIf { it.isNotBlank() } ?: return null
            return listOfNotNull(raw, unit?.takeIf { it.isNotBlank() }).joinToString(" ")
        }

    val displayStatus: LabResultStatus
        get() = if (isPending) LabResultStatus.PENDING else statusEnum

    /**
     * Withholding the green but keeping the word "Normal" would leave the
     * claim in place. An ungraded row is reported, not normal.
     */
    @get:StringRes
    val statusLabelRes: Int
        get() = if (isNormal && !isGradedNormal) R.string.lab_status_reported
        else displayStatus.labelRes

    val tone: StatusTone
        get() = if (isNormal && !isGradedNormal) StatusTone.NEUTRAL else displayStatus.tone

    /** The date worth showing: a pending row's `resultedAt` is not its own. */
    val displayDate: String? get() = if (isPending) collectedAt else (resultedAt ?: collectedAt)
}

/**
 * The statuses `PatientLabResultServiceImpl` can put on the wire — NORMAL,
 * ABNORMAL, ABNORMAL_LOW, ABNORMAL_HIGH, CRITICAL, and PENDING while the row
 * is unreleased. [UNKNOWN] is the app's own fallback for a value the backend
 * adds later; every `when` below is exhaustive and carries no `else`, so the
 * compiler refuses to build once a constant is added without a label.
 */
enum class LabResultStatus {
    PENDING,
    NORMAL,
    ABNORMAL,
    ABNORMAL_LOW,
    ABNORMAL_HIGH,
    CRITICAL,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            PENDING -> R.string.lab_status_pending
            NORMAL -> R.string.lab_status_normal
            ABNORMAL -> R.string.lab_status_abnormal
            ABNORMAL_LOW -> R.string.lab_status_abnormal_low
            ABNORMAL_HIGH -> R.string.lab_status_abnormal_high
            CRITICAL -> R.string.lab_status_critical
            UNKNOWN -> R.string.lab_status_unknown
        }

    val isAbnormal: Boolean
        get() = when (this) {
            ABNORMAL, ABNORMAL_LOW, ABNORMAL_HIGH -> true
            PENDING, NORMAL, CRITICAL, UNKNOWN -> false
        }

    val tone: StatusTone
        get() = when (this) {
            NORMAL -> StatusTone.POSITIVE
            ABNORMAL, ABNORMAL_LOW, ABNORMAL_HIGH -> StatusTone.ATTENTION
            CRITICAL -> StatusTone.NEGATIVE
            PENDING, UNKNOWN -> StatusTone.NEUTRAL
        }

    companion object {
        fun fromWire(raw: String?): LabResultStatus {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * How a status should read at a glance. Kept out of the UI layer so the
 * mapping is unit-testable and shared by every screen that shows a badge.
 */
enum class StatusTone { POSITIVE, ATTENTION, NEGATIVE, NEUTRAL }
