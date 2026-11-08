package com.example.hms.service.maternity;

import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportBaseDTO;
import com.example.hms.service.support.ClinicalContent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * QA D1525: the field lists of an ultrasound report's findings, in one place.
 *
 * <ul>
 *   <li>{@link #FIELDS}: the observations - the tri-state answers (cardiac
 *       activity, movement, tone, nasal bone, anomalies) and the fetus count.
 *       A stated one is a finding, "absent" included; nothing defaults them.</li>
 *   <li>{@link #FINDINGS}: the other findings (measurements, dates, written
 *       text). The empty-report rule counts both lists.</li>
 *   <li>{@link #SIGNS_OF_A_FETUS}: what "no fetus seen" contradicts.</li>
 *   <li>{@link #LEGACY_DEFAULTS}: what the old code filled before D1525.</li>
 * </ul>
 * {@code UltrasoundObservationFieldsTest} pins that the portal
 * (ultrasound-tab.ts) and V282 name the same fields.
 *
 * <p>Known limits (tasklist.md, standing debt): {@code observations_stated} is
 * per report, not per field, so once one observation changes on a legacy
 * report the other cleared ones read "Not assessed" from then on; and on a
 * signed legacy report a value equal to an old default reads "no answer" even
 * if the clinician typed it before signing (the stored value is unchanged).
 */
public final class UltrasoundObservations {

    /** One observation, read from a request and from a stored report. */
    public record Field(String name, Function<UltrasoundReportBaseDTO, Object> request,
                        Function<UltrasoundReport, Object> stored) {
    }

    /** One finding read from a request. */
    public record Finding(String name, Function<UltrasoundReportBaseDTO, Object> request) {
    }

    public static final List<Field> FIELDS = List.of(
        new Field("fetalCardiacActivity", UltrasoundReportBaseDTO::getFetalCardiacActivity,
            UltrasoundReport::getFetalCardiacActivity),
        new Field("fetalMovementObserved", UltrasoundReportBaseDTO::getFetalMovementObserved,
            UltrasoundReport::getFetalMovementObserved),
        new Field("fetalToneNormal", UltrasoundReportBaseDTO::getFetalToneNormal,
            UltrasoundReport::getFetalToneNormal),
        new Field("nasalBonePresent", UltrasoundReportBaseDTO::getNasalBonePresent,
            UltrasoundReport::getNasalBonePresent),
        new Field("anomaliesDetected", UltrasoundReportBaseDTO::getAnomaliesDetected,
            UltrasoundReport::getAnomaliesDetected),
        new Field("numberOfFetuses", UltrasoundReportBaseDTO::getNumberOfFetuses,
            UltrasoundReport::getNumberOfFetuses));

    /** The findings that are not observations: any one present makes a report non-empty. */
    public static final List<Finding> FINDINGS = List.of(
        new Finding("gestationalAgeAtScan", UltrasoundReportBaseDTO::getGestationalAgeAtScan),
        new Finding("gestationalAgeDays", UltrasoundReportBaseDTO::getGestationalAgeDays),
        new Finding("nuchalTranslucencyMm", UltrasoundReportBaseDTO::getNuchalTranslucencyMm),
        new Finding("crownRumpLengthMm", UltrasoundReportBaseDTO::getCrownRumpLengthMm),
        new Finding("estimatedDueDate", UltrasoundReportBaseDTO::getEstimatedDueDate),
        new Finding("fetalPosition", UltrasoundReportBaseDTO::getFetalPosition),
        new Finding("biparietalDiameterMm", UltrasoundReportBaseDTO::getBiparietalDiameterMm),
        new Finding("headCircumferenceMm", UltrasoundReportBaseDTO::getHeadCircumferenceMm),
        new Finding("abdominalCircumferenceMm", UltrasoundReportBaseDTO::getAbdominalCircumferenceMm),
        new Finding("femurLengthMm", UltrasoundReportBaseDTO::getFemurLengthMm),
        new Finding("estimatedFetalWeightGrams", UltrasoundReportBaseDTO::getEstimatedFetalWeightGrams),
        new Finding("placentalLocation", UltrasoundReportBaseDTO::getPlacentalLocation),
        new Finding("placentalGrade", UltrasoundReportBaseDTO::getPlacentalGrade),
        new Finding("amnioticFluidIndex", UltrasoundReportBaseDTO::getAmnioticFluidIndex),
        new Finding("amnioticFluidLevel", UltrasoundReportBaseDTO::getAmnioticFluidLevel),
        new Finding("cervicalLengthMm", UltrasoundReportBaseDTO::getCervicalLengthMm),
        new Finding("umbilicalArteryDoppler", UltrasoundReportBaseDTO::getUmbilicalArteryDoppler),
        new Finding("uterineArteryDoppler", UltrasoundReportBaseDTO::getUterineArteryDoppler),
        new Finding("fetalHeartRate", UltrasoundReportBaseDTO::getFetalHeartRate),
        new Finding("anatomyFindings", UltrasoundReportBaseDTO::getAnatomyFindings),
        new Finding("findingsSummary", UltrasoundReportBaseDTO::getFindingsSummary),
        new Finding("interpretation", UltrasoundReportBaseDTO::getInterpretation),
        new Finding("anomalyDescription", UltrasoundReportBaseDTO::getAnomalyDescription));

    /**
     * What "no fetus seen" (a count of 0) contradicts. Each field is shown and
     * editable on the report form, so a refusal always names something the
     * clinician can see. A sign is present when it is answered, except the
     * ones in {@link #SIGNS_ONLY_WHEN_TRUE}, which count only when TRUE (their
     * FALSE - no cardiac activity, no movement, no anomaly - fits an empty sac).
     */
    public static final List<Finding> SIGNS_OF_A_FETUS = List.of(
        new Finding("fetalCardiacActivity", UltrasoundReportBaseDTO::getFetalCardiacActivity),
        new Finding("fetalMovementObserved", UltrasoundReportBaseDTO::getFetalMovementObserved),
        new Finding("anomaliesDetected", UltrasoundReportBaseDTO::getAnomaliesDetected),
        new Finding("fetalHeartRate", UltrasoundReportBaseDTO::getFetalHeartRate),
        new Finding("biparietalDiameterMm", UltrasoundReportBaseDTO::getBiparietalDiameterMm),
        new Finding("headCircumferenceMm", UltrasoundReportBaseDTO::getHeadCircumferenceMm),
        new Finding("abdominalCircumferenceMm", UltrasoundReportBaseDTO::getAbdominalCircumferenceMm),
        new Finding("femurLengthMm", UltrasoundReportBaseDTO::getFemurLengthMm),
        new Finding("crownRumpLengthMm", UltrasoundReportBaseDTO::getCrownRumpLengthMm),
        new Finding("estimatedFetalWeightGrams", UltrasoundReportBaseDTO::getEstimatedFetalWeightGrams),
        new Finding("nuchalTranslucencyMm", UltrasoundReportBaseDTO::getNuchalTranslucencyMm),
        new Finding("nasalBonePresent", UltrasoundReportBaseDTO::getNasalBonePresent),
        new Finding("fetalToneNormal", UltrasoundReportBaseDTO::getFetalToneNormal),
        new Finding("anatomyFindings", UltrasoundReportBaseDTO::getAnatomyFindings));

    public static final Set<String> SIGNS_ONLY_WHEN_TRUE =
        Set.of("fetalCardiacActivity", "fetalMovementObserved", "anomaliesDetected");

    /**
     * The value the old code filled for each observation it defaulted, before
     * D1525 (V282 clears these on unreviewed legacy rows; the portal reads them
     * as "recorded before this was asked" on a legacy row). The nasal bone was
     * never defaulted.
     */
    public static final Map<String, Object> LEGACY_DEFAULTS = Map.of(
        "fetalCardiacActivity", Boolean.TRUE,
        "fetalMovementObserved", Boolean.TRUE,
        "fetalToneNormal", Boolean.TRUE,
        "anomaliesDetected", Boolean.FALSE,
        "numberOfFetuses", 1);

    private UltrasoundObservations() {
    }

    /** Every finding of the request, observations first: one list for the empty-report rule. */
    public static Object[] allFindings(UltrasoundReportBaseDTO request) {
        return Stream.concat(FIELDS.stream().map(f -> f.request().apply(request)),
            FINDINGS.stream().map(f -> f.request().apply(request))).toArray();
    }

    /** Whether the request answers any observation differently from the stored report. */
    public static boolean differ(UltrasoundReportBaseDTO request, UltrasoundReport stored) {
        return FIELDS.stream().anyMatch(f -> !Objects.equals(f.request().apply(request), f.stored().apply(stored)));
    }

    /** Whether the request carries any sign of a fetus ({@link #SIGNS_OF_A_FETUS}). */
    public static boolean hasSignOfAFetus(UltrasoundReportBaseDTO request) {
        return SIGNS_OF_A_FETUS.stream().anyMatch(sign -> {
            Object value = sign.request().apply(request);
            return SIGNS_ONLY_WHEN_TRUE.contains(sign.name())
                ? Boolean.TRUE.equals(value)
                : ClinicalContent.isAnswer(value);
        });
    }
}
