package com.example.hms.service.maternity;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * QA D1525: how a LEGACY ultrasound report is read and edited.
 *
 * <p>A report is legacy exactly when it existed before V282: V282 set
 * {@code observations_stated = FALSE} on every existing row and defaults the
 * column to TRUE, and the entity creates every new report as stated. Nothing
 * else makes a row legacy: a request that omits {@code observationsStated}
 * never does.
 *
 * <p>Before D1525 the server and the templates filled values nobody stated:
 * cardiac activity, movement and tone {@code TRUE}, anomalies detected
 * {@code FALSE}, one fetus, the category NORMAL, and the template's gestational
 * age (12 or 20 weeks). On a legacy row exactly those values are ambiguous and
 * read as unknown ({@code null}); every other value was typed by someone and is
 * kept: a stored {@code FALSE} flag, anomalies {@code TRUE}, two fetuses, a
 * category other than NORMAL.
 *
 * <p>The gestational age is a best-effort rule: it reads as unknown only while
 * the legacy row keeps the whole template signature (12 or 20 weeks, no days,
 * every text blank, no biometry, no heart rate, the flags at their defaults).
 * Known limit: a legacy row whose template age was kept while other fields were
 * typed shows that age as stated (tasklist.md, standing debt, Low).
 *
 * <p>Editing a legacy row: values a request sends that equal an old default
 * are treated as unknown ({@link #clearEchoedDefaults}) unless the request says
 * {@code observationsStated = true}, which the D1525 portal sends after showing
 * those fields as unknown; such a save makes the row stated.
 */
public final class LegacyUltrasoundDefaults {

    private static final int NT_TEMPLATE_WEEKS = 12;
    private static final int ANATOMY_TEMPLATE_WEEKS = 20;

    private LegacyUltrasoundDefaults() {
    }

    /** A legacy row: it existed before V282 ({@code observations_stated = FALSE}). */
    public static boolean legacy(UltrasoundReport report) {
        return Boolean.FALSE.equals(report.getObservationsStated());
    }

    /** A cardiac/movement/tone flag as read: a legacy TRUE is unknown, a FALSE was typed. */
    public static Boolean flag(UltrasoundReport report, Boolean value) {
        return legacy(report) && Boolean.TRUE.equals(value) ? null : value;
    }

    /** Anomalies detected as read: a legacy FALSE ("no anomalies") is unknown. */
    public static Boolean anomaliesDetected(UltrasoundReport report) {
        Boolean value = report.getAnomaliesDetected();
        return legacy(report) && Boolean.FALSE.equals(value) ? null : value;
    }

    /** The fetus count as read: a legacy 1 is unknown. */
    public static Integer numberOfFetuses(UltrasoundReport report) {
        Integer value = report.getNumberOfFetuses();
        return legacy(report) && Integer.valueOf(1).equals(value) ? null : value;
    }

    /** The category as read: a legacy NORMAL (the form's old preselection) is unknown. */
    public static UltrasoundFindingCategory findingCategory(UltrasoundReport report) {
        UltrasoundFindingCategory value = report.getFindingCategory();
        return legacy(report) && value == UltrasoundFindingCategory.NORMAL ? null : value;
    }

    /** The gestational age as read: unknown only on a legacy row still bearing the template signature. */
    public static Integer gestationalAgeAtScan(UltrasoundReport report) {
        boolean signature = templateSignature(report.getGestationalAgeAtScan(), report.getGestationalAgeDays(),
                Stream.of(report.getFindingsSummary(), report.getAnatomyFindings(), report.getInterpretation(),
                        report.getAnomalyDescription()),
                Stream.<Object>of(report.getBiparietalDiameterMm(), report.getHeadCircumferenceMm(),
                        report.getAbdominalCircumferenceMm(), report.getFemurLengthMm(),
                        report.getEstimatedFetalWeightGrams(), report.getNuchalTranslucencyMm(),
                        report.getCrownRumpLengthMm(), report.getFetalHeartRate()),
                Stream.of(report.getFetalCardiacActivity(), report.getFetalMovementObserved(),
                        report.getFetalToneNormal()));
        return legacy(report) && signature ? null : report.getGestationalAgeAtScan();
    }

    /**
     * An edit of a legacy row by a request that does not say it states the
     * observations: each value equal to an old default becomes unknown before
     * the empty-report check and the save, so an echoed default is neither
     * counted as a finding nor stored as one. A new report, a stated row, or a
     * request with {@code observationsStated = true} is left untouched.
     */
    public static void clearEchoedDefaults(UltrasoundReport report, UltrasoundReportRequestDTO request) {
        if (report == null || !legacy(report) || Boolean.TRUE.equals(request.getObservationsStated())) {
            return;
        }
        boolean signature = templateSignature(request.getGestationalAgeAtScan(), request.getGestationalAgeDays(),
                Stream.of(request.getFindingsSummary(), request.getAnatomyFindings(), request.getInterpretation(),
                        request.getAnomalyDescription()),
                Stream.<Object>of(request.getBiparietalDiameterMm(), request.getHeadCircumferenceMm(),
                        request.getAbdominalCircumferenceMm(), request.getFemurLengthMm(),
                        request.getEstimatedFetalWeightGrams(), request.getNuchalTranslucencyMm(),
                        request.getCrownRumpLengthMm(), request.getFetalHeartRate()),
                Stream.of(request.getFetalCardiacActivity(), request.getFetalMovementObserved(),
                        request.getFetalToneNormal()));
        if (Boolean.TRUE.equals(request.getFetalCardiacActivity())) {
            request.setFetalCardiacActivity(null);
        }
        if (Boolean.TRUE.equals(request.getFetalMovementObserved())) {
            request.setFetalMovementObserved(null);
        }
        if (Boolean.TRUE.equals(request.getFetalToneNormal())) {
            request.setFetalToneNormal(null);
        }
        if (Boolean.FALSE.equals(request.getAnomaliesDetected())) {
            request.setAnomaliesDetected(null);
        }
        if (Integer.valueOf(1).equals(request.getNumberOfFetuses())) {
            request.setNumberOfFetuses(null);
        }
        if (request.getFindingCategory() == UltrasoundFindingCategory.NORMAL) {
            request.setFindingCategory(null);
        }
        if (signature) {
            request.setGestationalAgeAtScan(null);
        }
    }

    private static boolean templateSignature(Integer weeks, Integer days, Stream<String> texts,
                                             Stream<Object> measurements, Stream<Boolean> flags) {
        if (weeks == null || (weeks != NT_TEMPLATE_WEEKS && weeks != ANATOMY_TEMPLATE_WEEKS) || days != null) {
            return false;
        }
        return texts.allMatch(t -> t == null || t.isBlank())
                && measurements.allMatch(Objects::isNull)
                && flags.allMatch(f -> f == null || Boolean.TRUE.equals(f));
    }
}
