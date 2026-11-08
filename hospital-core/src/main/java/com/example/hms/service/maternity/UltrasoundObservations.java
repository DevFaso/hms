package com.example.hms.service.maternity;

import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportBaseDTO;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * QA D1525: the observations of an ultrasound report in one place: the
 * tri-state answers (cardiac activity, movement, tone, nasal bone, anomalies)
 * and the fetus count. A stated one is a finding, "absent" included; nothing
 * defaults them any more.
 *
 * <p>Used by the empty-report rule and by the legacy flip
 * ({@code UltrasoundServiceImpl}); {@code UltrasoundObservationFieldsTest} pins
 * that the portal's lists and V282's data step name the same fields.
 */
public final class UltrasoundObservations {

    /** One observation, read from a request and from a stored report. */
    public record Field(String name, Function<UltrasoundReportBaseDTO, Object> request,
                        Function<UltrasoundReport, Object> stored) {
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

    /** The request's observations, in {@link #FIELDS} order. */
    public static Object[] of(UltrasoundReportBaseDTO request) {
        return FIELDS.stream().map(f -> f.request().apply(request)).toArray();
    }

    /** Whether the request answers any observation differently from the stored report. */
    public static boolean differ(UltrasoundReportBaseDTO request, UltrasoundReport stored) {
        return FIELDS.stream().anyMatch(f -> !Objects.equals(f.request().apply(request), f.stored().apply(stored)));
    }
}
