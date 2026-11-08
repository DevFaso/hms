package com.example.hms.service.maternity;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.model.UltrasoundReport;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * QA D1525: how a report whose observations were not written by a client that
 * states them (V282 {@code observations_stated} not TRUE) is read.
 *
 * <p>Before D1525 the server and the templates filled values nobody stated:
 * cardiac activity, movement and tone {@code TRUE}, anomalies detected
 * {@code FALSE}, one fetus, the category NORMAL, and the template's gestational
 * age (12 or 20 weeks). On such a row exactly those values are ambiguous and
 * are read as unknown ({@code null}); every other value was typed by someone
 * and is kept: a stored {@code FALSE} flag, anomalies {@code TRUE}, two
 * fetuses, a category other than NORMAL, a gestational age outside the
 * template signature.
 *
 * <p>The rule is exact for every row, whenever it was written: it touches only
 * values the old code could have defaulted, and only while the row is not
 * marked as stated. The response mapper and the patient timeline read through
 * here, so every consumer gets the same answer.
 */
public final class LegacyUltrasoundDefaults {

    private static final int NT_TEMPLATE_WEEKS = 12;
    private static final int ANATOMY_TEMPLATE_WEEKS = 20;

    private LegacyUltrasoundDefaults() {
    }

    /** Whether the row's observations are statements (written by a client that states them). */
    public static boolean stated(UltrasoundReport report) {
        return Boolean.TRUE.equals(report.getObservationsStated());
    }

    /** A cardiac/movement/tone flag as read: a legacy TRUE is unknown, a FALSE was typed. */
    public static Boolean flag(UltrasoundReport report, Boolean value) {
        return !stated(report) && Boolean.TRUE.equals(value) ? null : value;
    }

    /** Anomalies detected as read: a legacy FALSE ("no anomalies") is unknown. */
    public static Boolean anomaliesDetected(UltrasoundReport report) {
        Boolean value = report.getAnomaliesDetected();
        return !stated(report) && Boolean.FALSE.equals(value) ? null : value;
    }

    /** The fetus count as read: a legacy 1 is unknown. */
    public static Integer numberOfFetuses(UltrasoundReport report) {
        Integer value = report.getNumberOfFetuses();
        return !stated(report) && Integer.valueOf(1).equals(value) ? null : value;
    }

    /** The category as read: a legacy NORMAL (the form's old preselection) is unknown. */
    public static UltrasoundFindingCategory findingCategory(UltrasoundReport report) {
        UltrasoundFindingCategory value = report.getFindingCategory();
        return !stated(report) && value == UltrasoundFindingCategory.NORMAL ? null : value;
    }

    /**
     * The gestational age as read: unknown only when a legacy row still has the
     * template's signature (12 or 20 weeks, no days, every text blank, no
     * biometry, no heart rate, the flags at their defaults); otherwise kept.
     */
    public static Integer gestationalAgeAtScan(UltrasoundReport report) {
        Integer weeks = report.getGestationalAgeAtScan();
        return !stated(report) && hasTemplateSignature(report) ? null : weeks;
    }

    static boolean hasTemplateSignature(UltrasoundReport r) {
        Integer weeks = r.getGestationalAgeAtScan();
        if (weeks == null || (weeks != NT_TEMPLATE_WEEKS && weeks != ANATOMY_TEMPLATE_WEEKS)) {
            return false;
        }
        boolean textBlank = Stream.of(r.getFindingsSummary(), r.getAnatomyFindings(), r.getInterpretation(),
                r.getAnomalyDescription()).allMatch(t -> t == null || t.isBlank());
        boolean noMeasurement = Stream.of(r.getGestationalAgeDays(), r.getBiparietalDiameterMm(),
                r.getHeadCircumferenceMm(), r.getAbdominalCircumferenceMm(), r.getFemurLengthMm(),
                r.getEstimatedFetalWeightGrams(), r.getNuchalTranslucencyMm(), r.getCrownRumpLengthMm(),
                r.getFetalHeartRate()).allMatch(Objects::isNull);
        boolean flagsAtDefaults = Stream.of(r.getFetalCardiacActivity(), r.getFetalMovementObserved(),
                r.getFetalToneNormal()).allMatch(f -> f == null || Boolean.TRUE.equals(f));
        return textBlank && noMeasurement && flagsAtDefaults;
    }
}
