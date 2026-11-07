package com.example.hms.service.maternity;

import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.utility.ElapsedTime;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * QA D1427: the arguments of each labour alert's sentence, read back from the
 * row that raised it. Partograph entries and delivery records are never edited,
 * so these are the values the sentence was written with, for a row stored
 * before this class as well; {@code LaborAlertTextTest} holds every code of the
 * bundle to that, by comparing the rendering with the text the service stores.
 */
public final class LaborAlertArgs {

    public static final int FHR_BRADYCARDIA_BPM = 110;
    public static final int FHR_TACHYCARDIA_BPM = 160;
    public static final int ACTIVE_PHASE_DILATION_CM = 4;
    private static final double FULL_DILATION_CM = 10.0;
    private static final double MINUTES_PER_HOUR = 60.0;
    /** Shown for a pressure of the pair that was not measured. */
    private static final String NOT_MEASURED = "—";

    private LaborAlertArgs() {
    }

    /** WHO alert line: hours behind 1 cm/h from the active-phase anchor at 4 cm. */
    public static double lagHours(LocalDateTime anchor, LocalDateTime observationTime, int dilation) {
        double hours = ElapsedTime.minutesBetween(anchor, observationTime) / MINUTES_PER_HOUR;
        return Math.min(FULL_DILATION_CM, ACTIVE_PHASE_DILATION_CM + hours) - dilation;
    }

    /** A systolic or a diastolic alone is a real reading: the other one reads as a dash, never "null". */
    public static Object[] bloodPressure(Integer systolic, Integer diastolic) {
        return new Object[]{systolic == null ? NOT_MEASURED : systolic, diastolic == null ? NOT_MEASURED : diastolic};
    }

    /** The arguments of a partograph alert, or empty when the code or a value is unknown. */
    public static Optional<List<Object>> ofEntry(String code, LaborPartographEntry entry, LocalDateTime activePhaseStartAt) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code) {
            case "labor-fetal-bradycardia" -> known(entry.getFetalHeartRateBpm(), FHR_BRADYCARDIA_BPM);
            case "labor-fetal-tachycardia" -> known(entry.getFetalHeartRateBpm(), FHR_TACHYCARDIA_BPM);
            case "labor-liquor" -> entry.getLiquorColour() == null ? Optional.empty()
                : known(NotificationLocalizer.key("labor.liquor." + entry.getLiquorColour().name()));
            case "labor-action-line", "labor-alert-line" -> progress(entry, activePhaseStartAt);
            case "labor-maternal-fever" -> known(entry.getTemperatureCelsius());
            case "labor-maternal-tachycardia" -> known(entry.getPulseBpm());
            case "labor-severe-hypertension", "labor-hypertension" ->
                known(bloodPressure(entry.getSystolicBpMmHg(), entry.getDiastolicBpMmHg()));
            default -> Optional.empty();
        };
    }

    /** The arguments of a delivery alert, or empty when the code or a value is unknown. */
    public static Optional<List<Object>> ofDelivery(String code, DeliveryRecord delivery) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code) {
            case "labor-preterm" -> known(delivery.getGestationalAgeWeeksAtBirth());
            case "labor-weight-implausible" ->
                known(delivery.getBirthWeightGrams(), delivery.getGestationalAgeWeeksAtBirth());
            case "labor-pph" -> known(delivery.getEstimatedBloodLossMl());
            case "labor-apgar-critical", "labor-apgar-low" -> known(delivery.getApgarFiveMinute());
            case "labor-retained-placenta", "labor-stillbirth" -> Optional.of(List.of());
            default -> Optional.empty();
        };
    }

    private static Optional<List<Object>> progress(LaborPartographEntry entry, LocalDateTime anchor) {
        Integer dilation = entry.getCervicalDilationCm();
        if (dilation == null || anchor == null || entry.getObservationTime() == null) {
            return Optional.empty();
        }
        return Optional.of(List.of(dilation, lagHours(anchor, entry.getObservationTime(), dilation)));
    }

    private static Optional<List<Object>> known(Object... values) {
        return Arrays.stream(values).anyMatch(Objects::isNull) ? Optional.empty() : Optional.of(Arrays.asList(values));
    }
}
