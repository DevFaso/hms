package com.example.hms.service.maternity;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * QA D1525 (round 38): a finding is charted only when the clinician states it.
 *
 * <p>The nuchal-translucency and anatomy-scan templates used to pre-fill
 * cardiac activity, movement and tone as present, the finding category as
 * NORMAL, a gestational age and one fetus. Saved untouched, the report read
 * "cardiac activity present, movement, normal tone, NORMAL" although the
 * sonographer stated nothing, and the pre-filled gestational age satisfied the
 * "at least one finding" rule on its own. The sibling of CR-06: an alert never
 * records a treatment as performed.
 *
 * <p>The rules, one place for create and edit:
 * <ul>
 *   <li>A report must carry at least one measurement, observation or written
 *       finding ({@link #requireStatedFindings}). The templates no longer fill
 *       any of these, so a value here was entered by someone.</li>
 *   <li>The yes/no boxes (cardiac activity, movement, tone, nasal bone, anatomy
 *       survey complete) are not findings on their own, the QA D1182 rule: a scan
 *       with no cardiac activity says so in the findings or the interpretation.
 *       They are tri-state: {@code null} is "not assessed", and nothing on the
 *       server defaults them to {@code true} any more.</li>
 *   <li>One fetus is not a finding on its own (it was a default for years); more
 *       than one is.</li>
 *   <li>The finding category is chosen by the clinician
 *       ({@link #requireFindingCategory}); it is asked after the findings, so an
 *       untouched template is refused as an empty report first.</li>
 * </ul>
 * {@code UltrasoundReportFindingsTest} pins the rules and
 * {@code ClinicalTemplatePrefillGuardTest} fails when a template or a default
 * states a finding again.
 */
public final class UltrasoundReportFindings {

    /** The request property an empty report is refused against (the summary is where findings go). */
    public static final String EMPTY_REPORT_FIELD = "findingsSummary";
    public static final String EMPTY_REPORT_KEY = "ultrasound.report.noStatedFindings";
    public static final String CATEGORY_FIELD = "findingCategory";
    public static final String CATEGORY_KEY = "ultrasoundReport.findingCategory.required";

    private UltrasoundReportFindings() {
    }

    /** Refuses a report in which the clinician stated no finding. */
    public static void requireStatedFindings(UltrasoundReportRequestDTO request) {
        if (!hasStatedFinding(request)) {
            throw new FieldBusinessException(EMPTY_REPORT_FIELD, EMPTY_REPORT_KEY);
        }
    }

    /** Refuses a report whose finding category nobody chose. */
    public static void requireFindingCategory(UltrasoundReportRequestDTO request) {
        if (request == null || request.getFindingCategory() == null) {
            throw new FieldBusinessException(CATEGORY_FIELD, CATEGORY_KEY);
        }
    }

    /** Whether at least one measurement, observation or written finding is present. */
    public static boolean hasStatedFinding(UltrasoundReportRequestDTO request) {
        if (request == null) {
            return false;
        }
        Integer fetuses = request.getNumberOfFetuses();
        if (fetuses != null && fetuses > 1) {
            return true;
        }
        return findings(request).stream().anyMatch(UltrasoundReportFindings::isPresent);
    }

    private static List<Object> findings(UltrasoundReportRequestDTO r) {
        return Arrays.asList(
            r.getGestationalAgeAtScan(), r.getGestationalAgeDays(), r.getNuchalTranslucencyMm(),
            r.getCrownRumpLengthMm(), r.getEstimatedDueDate(), r.getFetalPosition(),
            r.getBiparietalDiameterMm(), r.getHeadCircumferenceMm(), r.getAbdominalCircumferenceMm(),
            r.getFemurLengthMm(), r.getEstimatedFetalWeightGrams(), r.getPlacentalLocation(),
            r.getPlacentalGrade(), r.getAmnioticFluidIndex(), r.getAmnioticFluidLevel(),
            r.getCervicalLengthMm(), r.getUmbilicalArteryDoppler(), r.getUterineArteryDoppler(),
            r.getFetalHeartRate(), r.getAnatomyFindings(), r.getFindingsSummary(), r.getInterpretation(),
            r.getAnomalyDescription());
    }

    private static boolean isPresent(Object value) {
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        return Objects.nonNull(value);
    }
}
