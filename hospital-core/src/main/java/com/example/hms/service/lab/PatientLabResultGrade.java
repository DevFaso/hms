package com.example.hms.service.lab;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;

import java.util.Locale;
import java.util.Set;

/**
 * QA D1740: the ONE patient-facing reading of a lab result, its status
 * (NORMAL, ABNORMAL_HIGH...) and the reference range shown beside it. The
 * patient's lab results ({@code PatientLabResultServiceImpl}) and the
 * after-visit summary ({@code AfterVisitSummarySections}) both read it here,
 * on top of {@link LabResultMapper}'s grading, so a value can never read
 * "Élevé" on one screen and bare on the other. The summary used to copy the
 * row's raw {@code abnormalFlag} and {@code referenceRange}, which are null
 * when the grade comes from the test definition's ranges.
 */
public final class PatientLabResultGrade {

    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_ABNORMAL = "ABNORMAL";
    public static final String STATUS_ABNORMAL_HIGH = "ABNORMAL_HIGH";
    public static final String STATUS_ABNORMAL_LOW = "ABNORMAL_LOW";
    public static final String STATUS_CRITICAL = "CRITICAL";
    public static final String STATUS_PENDING = "PENDING";
    /**
     * D31: released, but nothing graded it — no reference range applies and
     * no flag was recorded. Reporting NORMAL there falsely reassures the
     * reader (28.5 mmol/L glucose read "normal" because the test had no
     * range). Every client already renders UNKNOWN as "not interpreted".
     */
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    /** QA D240: released, with a non-numeric answer (Positif, Négatif...). */
    public static final String STATUS_QUALITATIVE = "QUALITATIVE";

    /** The statuses that are a grade: PENDING, UNKNOWN and QUALITATIVE are not. */
    private static final Set<String> GRADES = Set.of(STATUS_NORMAL, STATUS_ABNORMAL, STATUS_ABNORMAL_HIGH,
        STATUS_ABNORMAL_LOW, STATUS_CRITICAL);

    private PatientLabResultGrade() {
    }

    /**
     * The status the patient's lab results show for {@code result}, given
     * {@code mapped}, the mapper's reading of the same row, and {@code value},
     * the value the caller shows with it (this class shows none itself).
     *
     * <p>B18 — two gradings can exist for one row: what the configured
     * reference range says about the value, and the flag the analyzer
     * (OBX-8) or the technologist recorded. Neither is discarded: the
     * patient sees the MORE SEVERE of the two, with the direction taken
     * from whichever source is directional (the range wins when both
     * are, since it is the hospital's own range). So a row inside its
     * range that the analyzer still flagged H reads ABNORMAL_HIGH, and an
     * HH row never reads merely ABNORMAL_HIGH because the range was mild.
     */
    public static String statusOf(LabResult result, String value, LabResultResponseDTO mapped) {
        if (!result.isReleased()) {
            return STATUS_PENDING;
        }
        String fromRange = statusFromRange(mapped != null ? mapped.getSeverityFlag() : null);
        String fromFlag = statusOf(result.getAbnormalFlag());
        if (fromRange == null) {
            // QA D240: "Positif" on a malaria RDT is not "unknown": it is a
            // qualitative answer that no numeric range can grade. Say so,
            // rather than "Inconnu" on a released result.
            if (STATUS_UNKNOWN.equals(fromFlag) && isQualitative(value)) {
                return STATUS_QUALITATIVE;
            }
            // QA D1394: an analyser's NORMAL does not grade a value the hospital's ranges could not.
            return mapped != null && mapped.isGradeUnknown() ? STATUS_UNKNOWN : fromFlag;
        }
        int rangeRank = rank(fromRange);
        int flagRank = rank(fromFlag);
        if (rangeRank != flagRank) {
            return rangeRank > flagRank ? fromRange : fromFlag;
        }
        return isDirectional(fromRange) || !isDirectional(fromFlag) ? fromRange : fromFlag;
    }

    /**
     * The grade the after-visit summary flags: {@link #statusOf} when it is
     * one (NORMAL, ABNORMAL, ABNORMAL_LOW, ABNORMAL_HIGH, CRITICAL), else null.
     */
    public static String gradeOf(LabResult result, String value, LabResultResponseDTO mapped) {
        String status = statusOf(result, value, mapped);
        return GRADES.contains(status) ? status : null;
    }

    /**
     * QA D834: what the patient reads after "Normal:". The graded numeric
     * range; for a qualitative test, which has none, its expected answer
     * ("Négatif"). The fake 0-0 range a qualitative test once needed read
     * "Normal: 0 - 0" beside "Résultat: 1".
     *
     * @param graded the range the mapper graded the row against
     *               ({@link LabResultMapper#gradedReferenceRange})
     */
    public static String referenceTextOf(LabResultReferenceRangeDTO graded, LabTestDefinition testDefinition) {
        String range = formatReferenceRange(graded);
        if (range != null) {
            return range;
        }
        return testDefinition != null && testDefinition.hasExpectedResult()
            ? testDefinition.getExpectedResult().trim()
            : null;
    }

    /** A word answer with no digit in it; "<0.5" or "9.2 g/dL" is numeric, just ungraded. */
    private static boolean isQualitative(String value) {
        return value != null && !value.isBlank() && value.chars().noneMatch(Character::isDigit);
    }

    /**
     * What the configured reference range says, or null when there is
     * none to grade against. Out of range is ABNORMAL with a direction,
     * nothing more: CRITICAL comes only from a mapper severity that is
     * itself CRITICAL or from the recorded flag. (An earlier reading
     * upgraded an unacknowledged HIGH to CRITICAL as a review nudge; once
     * merged with the analyzer flag that synthetic CRITICAL outranked an
     * explicit N and reached the patient unreviewed.)
     */
    private static String statusFromRange(String severity) {
        if (severity == null || severity.isBlank() || LabResultMapper.FLAG_UNSPECIFIED.equalsIgnoreCase(severity)) {
            return null;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case STATUS_CRITICAL -> STATUS_CRITICAL;
            case "HIGH" -> STATUS_ABNORMAL_HIGH;
            case "LOW" -> STATUS_ABNORMAL_LOW;
            // QA D286: a qualitative answer that reads positive.
            case LabResultMapper.FLAG_ABNORMAL -> STATUS_ABNORMAL;
            default -> STATUS_NORMAL;
        };
    }

    private static int rank(String status) {
        return switch (status) {
            case STATUS_CRITICAL -> 3;
            case STATUS_ABNORMAL_HIGH, STATUS_ABNORMAL_LOW, STATUS_ABNORMAL -> 2;
            default -> 1;
        };
    }

    private static boolean isDirectional(String status) {
        return STATUS_ABNORMAL_HIGH.equals(status) || STATUS_ABNORMAL_LOW.equals(status);
    }

    private static String statusOf(AbnormalFlag flag) {
        if (flag == null) {
            return STATUS_UNKNOWN;
        }
        return switch (flag) {
            case CRITICAL -> STATUS_CRITICAL;
            case ABNORMAL_HIGH -> STATUS_ABNORMAL_HIGH;
            case ABNORMAL_LOW -> STATUS_ABNORMAL_LOW;
            case ABNORMAL -> STATUS_ABNORMAL;
            case NORMAL -> STATUS_NORMAL;
        };
    }

    /**
     * The range the row was graded against — {@link LabResultMapper#gradedReferenceRange},
     * the same selection that produced its severity — labelled ONLY with the
     * unit configured on that range.
     *
     * <p>This used to format the first configured range whatever the grading
     * had picked, and to stamp the RESULT's unit onto a range that carried
     * none. The first showed a value graded in mmol/L beside mg/dL limits; the
     * second asserted a unit nobody configured, and made the mismatch
     * undetectable to a client that checks the displayed unit against the
     * row's. A unitless range is therefore shown as bare numbers: the limits
     * are real, the unit is not known, and a client can see that it is not.
     */
    private static String formatReferenceRange(LabResultReferenceRangeDTO range) {
        if (range == null) {
            return null;
        }
        Double min = range.getMinValue();
        Double max = range.getMaxValue();
        String unit = range.getUnit();
        String unitSuffix = unit != null && !unit.isBlank() ? " " + unit.trim() : "";

        if (min != null && max != null) {
            return formatNumber(min) + " - " + formatNumber(max) + unitSuffix;
        }
        if (min != null) {
            return ">= " + formatNumber(min) + unitSuffix;
        }
        if (max != null) {
            return "<= " + formatNumber(max) + unitSuffix;
        }
        return null;
    }

    private static String formatNumber(Double value) {
        return new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(Locale.ROOT))
            .format(value);
    }
}
