package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rules on a hand-entered lab result value, shared by create and amend.
 *
 * <ul>
 *   <li>QA D288: a test whose reference ranges carry numeric limits is a
 *       numeric test, so its value must be a number ("abc" was stored), and
 *       may be negative only when one of its limits is (base excess is, a
 *       haemoglobin is not; -1 was stored).</li>
 *   <li>QA D286: a qualitative answer that reads positive ("Positif" on a
 *       malaria RDT) is flagged ABNORMAL, since no numeric range can grade it
 *       and it otherwise reached the in-basket as "not evaluated" and the
 *       patient portal with a green check.</li>
 * </ul>
 */
public final class LabResultValueRules {

    /** Positive / detected / reactive in EN, FR and ES, as the whole answer or its first word. */
    private static final Pattern POSITIVE = Pattern.compile(
        "^(POSITI(F|VE|VO|VA)|DETECTE|DETECTED|DETECTADO|DETECTADA|REACTIF|REACTIVE|REACTIVO|REACTIVA"
            + "|PRESENT|PRESENTE|PRESENCE|\\+{1,4})(?![A-Z]).*");

    /** The comparator of a censored value, "<", ">", "<=", ">=" or their single-character forms, and the spaces after it. */
    private static final Pattern CENSOR_PREFIX = Pattern.compile("^(<=|>=|<|>|\u2264|\u2265)\\s*");

    private LabResultValueRules() {
    }

    /** Refuses a value a numeric test cannot hold; does nothing for a test with no numeric limits. */
    static void requireValidValue(String rawValue, LabTestDefinition definition) {
        if (rawValue == null || rawValue.isBlank() || definition == null) {
            return;
        }
        List<LabTestReferenceRange> ranges = definition.getReferenceRanges();
        if (ranges == null || ranges.stream().noneMatch(LabResultValueRules::hasNumericLimit)) {
            return;
        }
        double value;
        try {
            // A censored value ("<0.5", ">200", "<=20") is a number with a
            // comparator: the analyzer's or the method's limit of detection.
            value = Double.parseDouble(CENSOR_PREFIX.matcher(rawValue.trim()).replaceFirst(""));
        } catch (NumberFormatException ex) {
            throw new BusinessException("lab.result.value.notNumeric");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new BusinessException("lab.result.value.notNumeric");
        }
        if (value < 0 && ranges.stream().noneMatch(LabResultValueRules::hasNegativeLimit)) {
            throw new BusinessException("lab.result.value.negative");
        }
    }

    /** True when the value is a qualitative answer that reads positive. */
    public static boolean isPositiveQualitative(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        String trimmed = rawValue.trim();
        try {
            Double.parseDouble(trimmed.replace(',', '.'));
            return false;
        } catch (NumberFormatException ignored) {
            // not a number: a qualitative answer
        }
        String folded = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT);
        return POSITIVE.matcher(folded).matches();
    }

    /**
     * The flag a hand-entered value carries: ABNORMAL for a positive
     * qualitative answer; otherwise the existing flag, except that an
     * ABNORMAL this rule set on the previous value is withdrawn when the
     * value no longer reads positive.
     */
    static AbnormalFlag flagFor(String newValue, String previousValue, AbnormalFlag current) {
        if (isPositiveQualitative(newValue)) {
            return current == AbnormalFlag.CRITICAL ? current : AbnormalFlag.ABNORMAL;
        }
        if (current == AbnormalFlag.ABNORMAL && isPositiveQualitative(previousValue)) {
            return null;
        }
        return current;
    }

    private static boolean hasNumericLimit(LabTestReferenceRange range) {
        return range != null && (range.getMinValue() != null || range.getMaxValue() != null
            || range.getCriticalLow() != null || range.getCriticalHigh() != null);
    }

    private static boolean hasNegativeLimit(LabTestReferenceRange range) {
        return range != null && (isNegative(range.getMinValue()) || isNegative(range.getMaxValue())
            || isNegative(range.getCriticalLow()) || isNegative(range.getCriticalHigh()));
    }

    private static boolean isNegative(Double limit) {
        return limit != null && limit < 0;
    }
}
