package com.example.hms.service.maternity;

import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportBaseDTO;
import com.example.hms.service.support.ClinicalContent;

import java.util.LinkedHashMap;
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
 *       text). The empty-report rule counts both lists, on a request
 *       ({@link #allFindings(UltrasoundReportBaseDTO)}) and on a stored report
 *       before review or notification ({@link #allFindings(UltrasoundReport)}).</li>
 *   <li>{@link #SIGNS_OF_A_FETUS}: what "no fetus seen" contradicts, built from
 *       the two lists above.</li>
 *   <li>{@link #LEGACY_DEFAULTS}: what the old code filled before D1525.</li>
 * </ul>
 * {@code UltrasoundObservationFieldsTest} pins that the portal
 * (ultrasound-tab.ts) and V282 name the same fields.
 *
 * <p>Known limits (tasklist.md, standing debt): {@code observations_stated} is
 * per report, not per field, so once one observation changes on a legacy
 * report the other cleared ones read "Not assessed" from then on; on a signed
 * legacy report a value equal to an old default reads "no answer" even if the
 * clinician typed it before signing (the stored value is unchanged); only the
 * ultrasound tab reads a signed legacy report's old defaults that way - the
 * timeline, the doctor record, exports and the apps show the stored signed
 * values as they are (reviewed legacy rows only: V282 cleared the unreviewed
 * ones); and a row an older instance writes during the rolling deploy keeps its
 * defaults with the legacy mark, so a summary-only edit through the new portal
 * (which loads those defaults as not assessed) marks it stated and clears them,
 * and re-stating a value equal to the stored default is not recorded as stated.
 */
public final class UltrasoundObservations {

    /** The observation names, shared by the lists below (and the portal's). */
    public static final String CARDIAC_ACTIVITY = "fetalCardiacActivity";
    public static final String MOVEMENT = "fetalMovementObserved";
    public static final String TONE = "fetalToneNormal";
    public static final String NASAL_BONE = "nasalBonePresent";
    public static final String ANOMALIES = "anomaliesDetected";
    public static final String FETUS_COUNT = "numberOfFetuses";

    /** One field, read from a request and from a stored report. */
    public record Field(String name, Function<UltrasoundReportBaseDTO, Object> request,
                        Function<UltrasoundReport, Object> stored) {
    }

    public static final List<Field> FIELDS = List.of(
        new Field(CARDIAC_ACTIVITY, UltrasoundReportBaseDTO::getFetalCardiacActivity,
            UltrasoundReport::getFetalCardiacActivity),
        new Field(MOVEMENT, UltrasoundReportBaseDTO::getFetalMovementObserved,
            UltrasoundReport::getFetalMovementObserved),
        new Field(TONE, UltrasoundReportBaseDTO::getFetalToneNormal, UltrasoundReport::getFetalToneNormal),
        new Field(NASAL_BONE, UltrasoundReportBaseDTO::getNasalBonePresent, UltrasoundReport::getNasalBonePresent),
        new Field(ANOMALIES, UltrasoundReportBaseDTO::getAnomaliesDetected, UltrasoundReport::getAnomaliesDetected),
        new Field(FETUS_COUNT, UltrasoundReportBaseDTO::getNumberOfFetuses, UltrasoundReport::getNumberOfFetuses));

    /** The findings that are not observations: any one present makes a report non-empty. */
    public static final List<Field> FINDINGS = List.of(
        new Field("gestationalAgeAtScan", UltrasoundReportBaseDTO::getGestationalAgeAtScan,
            UltrasoundReport::getGestationalAgeAtScan),
        new Field("gestationalAgeDays", UltrasoundReportBaseDTO::getGestationalAgeDays,
            UltrasoundReport::getGestationalAgeDays),
        new Field("nuchalTranslucencyMm", UltrasoundReportBaseDTO::getNuchalTranslucencyMm,
            UltrasoundReport::getNuchalTranslucencyMm),
        new Field("crownRumpLengthMm", UltrasoundReportBaseDTO::getCrownRumpLengthMm,
            UltrasoundReport::getCrownRumpLengthMm),
        new Field("estimatedDueDate", UltrasoundReportBaseDTO::getEstimatedDueDate,
            UltrasoundReport::getEstimatedDueDate),
        new Field("fetalPosition", UltrasoundReportBaseDTO::getFetalPosition, UltrasoundReport::getFetalPosition),
        new Field("biparietalDiameterMm", UltrasoundReportBaseDTO::getBiparietalDiameterMm,
            UltrasoundReport::getBiparietalDiameterMm),
        new Field("headCircumferenceMm", UltrasoundReportBaseDTO::getHeadCircumferenceMm,
            UltrasoundReport::getHeadCircumferenceMm),
        new Field("abdominalCircumferenceMm", UltrasoundReportBaseDTO::getAbdominalCircumferenceMm,
            UltrasoundReport::getAbdominalCircumferenceMm),
        new Field("femurLengthMm", UltrasoundReportBaseDTO::getFemurLengthMm, UltrasoundReport::getFemurLengthMm),
        new Field("estimatedFetalWeightGrams", UltrasoundReportBaseDTO::getEstimatedFetalWeightGrams,
            UltrasoundReport::getEstimatedFetalWeightGrams),
        new Field("placentalLocation", UltrasoundReportBaseDTO::getPlacentalLocation,
            UltrasoundReport::getPlacentalLocation),
        new Field("placentalGrade", UltrasoundReportBaseDTO::getPlacentalGrade, UltrasoundReport::getPlacentalGrade),
        new Field("amnioticFluidIndex", UltrasoundReportBaseDTO::getAmnioticFluidIndex,
            UltrasoundReport::getAmnioticFluidIndex),
        new Field("amnioticFluidLevel", UltrasoundReportBaseDTO::getAmnioticFluidLevel,
            UltrasoundReport::getAmnioticFluidLevel),
        new Field("cervicalLengthMm", UltrasoundReportBaseDTO::getCervicalLengthMm,
            UltrasoundReport::getCervicalLengthMm),
        new Field("umbilicalArteryDoppler", UltrasoundReportBaseDTO::getUmbilicalArteryDoppler,
            UltrasoundReport::getUmbilicalArteryDoppler),
        new Field("uterineArteryDoppler", UltrasoundReportBaseDTO::getUterineArteryDoppler,
            UltrasoundReport::getUterineArteryDoppler),
        new Field("fetalHeartRate", UltrasoundReportBaseDTO::getFetalHeartRate, UltrasoundReport::getFetalHeartRate),
        new Field("anatomyFindings", UltrasoundReportBaseDTO::getAnatomyFindings,
            UltrasoundReport::getAnatomyFindings),
        new Field("findingsSummary", UltrasoundReportBaseDTO::getFindingsSummary,
            UltrasoundReport::getFindingsSummary),
        new Field("interpretation", UltrasoundReportBaseDTO::getInterpretation, UltrasoundReport::getInterpretation),
        new Field("anomalyDescription", UltrasoundReportBaseDTO::getAnomalyDescription,
            UltrasoundReport::getAnomalyDescription));

    /**
     * What "no fetus seen" (a count of 0) contradicts: the observations below
     * from {@link #FIELDS} and the fetal measurements from {@link #FINDINGS}.
     * Each is shown and editable on the report form. A sign is present when it
     * is answered, except the ones in {@link #SIGNS_ONLY_WHEN_TRUE}, which count
     * only when TRUE (no heart, no movement, no anomaly fit an empty sac). Free
     * text (anatomy findings) is deliberately not a sign: "gestational sac 25
     * mm, no embryo seen" supports a count of 0.
     */
    public static final List<Field> SIGNS_OF_A_FETUS = Stream.concat(
            byName(FIELDS, CARDIAC_ACTIVITY, MOVEMENT, ANOMALIES, NASAL_BONE, TONE),
            byName(FINDINGS, "fetalHeartRate", "biparietalDiameterMm", "headCircumferenceMm",
                "abdominalCircumferenceMm", "femurLengthMm", "crownRumpLengthMm", "estimatedFetalWeightGrams",
                "nuchalTranslucencyMm"))
        .toList();

    public static final Set<String> SIGNS_ONLY_WHEN_TRUE = Set.of(CARDIAC_ACTIVITY, MOVEMENT, ANOMALIES);

    /**
     * What the contradiction rules read, each name once: the category, the
     * anomaly description, the observations and the signs of a fetus. On a
     * legacy report the rules run only when one of these changes (a typo fix on
     * an old contradictory report still saves); on a new or stated report,
     * always.
     */
    public static final List<Field> CONTRADICTION_INPUTS = distinct(Stream.of(
            Stream.of(new Field("findingCategory", UltrasoundReportBaseDTO::getFindingCategory,
                UltrasoundReport::getFindingCategory)),
            byName(FINDINGS, "anomalyDescription"), FIELDS.stream(), SIGNS_OF_A_FETUS.stream())
        .flatMap(f -> f));

    /**
     * The value the old code filled for each observation it defaulted, before
     * D1525 (V282 clears these on unreviewed legacy rows; the portal reads them
     * as "recorded before this was asked" on a legacy row). The nasal bone was
     * never defaulted.
     */
    public static final Map<String, Object> LEGACY_DEFAULTS = Map.of(
        CARDIAC_ACTIVITY, Boolean.TRUE,
        MOVEMENT, Boolean.TRUE,
        TONE, Boolean.TRUE,
        ANOMALIES, Boolean.FALSE,
        FETUS_COUNT, 1);

    private UltrasoundObservations() {
    }

    private static Stream<Field> byName(List<Field> fields, String... names) {
        return Stream.of(names).map(name -> fields.stream().filter(f -> f.name().equals(name)).findFirst()
            .orElseThrow());
    }

    private static List<Field> distinct(Stream<Field> fields) {
        Map<String, Field> byName = new LinkedHashMap<>();
        fields.forEach(f -> byName.putIfAbsent(f.name(), f));
        return List.copyOf(byName.values());
    }

    /** Every finding of the request, observations first: one list for the empty-report rule. */
    public static Object[] allFindings(UltrasoundReportBaseDTO request) {
        return Stream.concat(FIELDS.stream(), FINDINGS.stream()).map(f -> f.request().apply(request)).toArray();
    }

    /** Every finding of a stored report: the same rule before it is reviewed or the patient told. */
    public static Object[] allFindings(UltrasoundReport stored) {
        return Stream.concat(FIELDS.stream(), FINDINGS.stream()).map(f -> f.stored().apply(stored)).toArray();
    }

    /** Whether the request answers any observation differently from the stored report. */
    public static boolean differ(UltrasoundReportBaseDTO request, UltrasoundReport stored) {
        return differ(FIELDS, request, stored);
    }

    /**
     * Whether the request differs from the stored report on any of
     * {@code fields}. Blank text equals null, as the portal treats a cleared
     * textarea.
     */
    public static boolean differ(List<Field> fields, UltrasoundReportBaseDTO request, UltrasoundReport stored) {
        return fields.stream().anyMatch(f ->
            !Objects.equals(blankAsNull(f.request().apply(request)), blankAsNull(f.stored().apply(stored))));
    }

    private static Object blankAsNull(Object value) {
        return value instanceof CharSequence text && text.toString().isBlank() ? null : value;
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
