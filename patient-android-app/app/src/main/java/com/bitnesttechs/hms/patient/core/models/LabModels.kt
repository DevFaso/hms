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
            if (raw.isEmpty() || raw.toDoubleOrNull() == null) return false
            return referenceRangeApplies
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

    /**
     * Whether the reference range on the wire is the one THIS result was
     * measured against.
     *
     * The range shown and the range graded against are not necessarily the
     * same: `formatReferenceRange` always formats `ranges[0]`, while
     * `determineSeverityFlag` grades against `findMatchingRange(resultUnit,
     * …)`. On a test configured with two unit-specific ranges, the row can be
     * graded NORMAL in mmol/L and displayed against the mg/dL limits.
     *
     * NOT complete cover, and it cannot be from here: when `ranges[0]` has no
     * unit of its own, `formatReferenceRange` stamps the RESULT's unit onto
     * its numbers, so the displayed string always carries this row's unit,
     * this check always passes, and the limits are mislabelled with a unit
     * they were never expressed in. That is a server-side defect and is filed
     * as one; nothing the app can see distinguishes it.
     */
    val referenceRangeApplies: Boolean
        get() {
            val unit = unit?.trim().orEmpty()
            if (unit.isEmpty()) return true
            return rangeIsInUnit(referenceRange.orEmpty().trimEnd(), unit)
        }

    /** The reference range to put in front of the patient. */
    val displayReferenceRange: String?
        get() = referenceRange?.takeIf { it.isNotBlank() && !isPending }

    /**
     * True when the displayed limits MAY not be in the result's units, so the
     * UI can caveat them.
     *
     * Deliberately a caveat rather than a suppression. `findMatchingRange`
     * falls back to `referenceRanges.get(0)` when no configured range matches
     * the result unit — and that is exactly the range `formatReferenceRange`
     * displays — so on the ordinary single-range test whose configured unit
     * string merely differs cosmetically from the result's (`µmol/L` vs
     * `umol/L`, `x10^9/L` vs `10^9/L`, `mm Hg` vs `mmHg`), the range shown IS
     * the range graded against and there is nothing wrong at all. The app
     * cannot tell that apart from the real multi-range mismatch, so hiding
     * the limits would blank correct data on what is probably the common
     * case. The green tick is still withheld either way: under-reassuring is
     * free, deleting a patient's reference range is not.
     */
    val referenceRangeUnitUncertain: Boolean
        get() = !isPending && !referenceRange.isNullOrBlank() && !referenceRangeApplies

    /**
     * Whether a formatted reference range is expressed in [unit].
     *
     * A SUBSTRING test is not enough: `g/dL` is a substring of `mg/dL`,
     * `mol/L` of `mmol/L`, `U/L` of `mU/L` — so the very mismatch this guards
     * against would pass it. `formatReferenceRange` appends `" " + unit`, so
     * the unit is the suffix and the character before it is a separator;
     * requiring that boundary also keeps units that contain digits
     * (`x10^9/L`) working, which trailing-non-digit extraction would not.
     *
     * An empty range is accepted: there is no unit to disagree about.
     */
    private fun rangeIsInUnit(range: String, unit: String): Boolean {
        // No range at all: nothing to disagree about. There is deliberately NO
        // "ends in a digit, so it carries no unit" shortcut — `cells/mm3`,
        // `10^9/L` and `mmol/24h` all end in one, and such a shortcut handed
        // every CD4 count an unconditional pass. It would also be unreachable:
        // `formatReferenceRange` falls back to the RESULT's unit when
        // `ranges[0]` has none, so whenever this row has a unit the formatted
        // range carries one too.
        if (range.isBlank()) return true
        val haystack = normalizedUnit(range)
        val needle = normalizedUnit(unit)
        if (needle.isEmpty()) return true
        if (!haystack.endsWith(needle)) return false
        val boundary = haystack.length - needle.length - 1
        if (boundary < 0) return true
        val preceding = haystack[boundary]
        // `formatReferenceRange` emits "<numbers> <unit>", so once the spaces
        // are folded away the character before a WHOLE unit is always the last
        // digit of the numbers. Anything else means the suffix cut a longer
        // unit in half — a letter for `g/dl` inside `mg/dl`, a `/` for `l`
        // inside `mmol/l`, which an "only reject letters" rule waved through.
        // The single exception is the `x` of the `x10^9/L` multiplication
        // marker; no real unit ends `…xg/dL`.
        return preceding.isDigit() || preceding == 'x'
    }

    /**
     * Enough normalisation that a purely COSMETIC difference between the
     * configured range's unit and the result's does not read as a real one.
     *
     * `findMatchingRange` compares `trim().equalsIgnoreCase(...)` and falls
     * back to `ranges[0]`, so `mm Hg` vs `mmHg`, `µmol/L` vs `umol/L` and
     * `x10^9/L` vs `10^9/L` all end up grading against the range on screen —
     * the app must not caveat those. Case, whitespace, the two micro signs
     * and a leading multiplication marker are therefore folded away. What is
     * deliberately NOT folded is an SI prefix: `mg` and `g` are a
     * thousandfold apart and that is the disagreement worth flagging.
     */
    private fun normalizedUnit(raw: String): String =
        raw.lowercase()
            .replace('\u00B5', 'u') // MICRO SIGN
            .replace('\u03BC', 'u') // GREEK SMALL LETTER MU
            .filterNot { it.isWhitespace() }
            // "mcg" is the safety-preferred spelling of µg — the same unit,
            // and common on hand-entered ranges.
            .replace("mcg", "ug")
            // "UI" is the French spelling of IU. Bounded so it cannot eat the
            // middle of another token.
            .replace(UI_TOKEN, "iu")
            .removePrefix("x")
            .removePrefix("*")

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

/** `ui` only where it stands alone as a token — see `normalizedUnit`. */
private val UI_TOKEN = Regex("(?<![a-z])ui(?![a-z])")
