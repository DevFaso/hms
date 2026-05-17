package com.example.hms.payload.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Operational throughput KPI rollup for the row-32 dashboard.
 *
 * <p>Three load-bearing care-delivery indicators:
 * <ul>
 *   <li>door-to-doctor — average minutes between
 *       {@code encounter.arrival_timestamp} and
 *       {@code encounter.triage_timestamp}, per the V36 / V37 check-in
 *       and triage column set;</li>
 *   <li>dispense lead time — average minutes between a prescription's
 *       creation and its first dispense event;</li>
 *   <li>no-show rate — fraction of appointments in the requested
 *       window whose status is {@code NO_SHOW}.</li>
 * </ul>
 *
 * <p>All three KPIs are tenant-scoped via
 * {@code HospitalContextHolder} on the service. Counts feed the
 * Angular analytics module's KPI cards via
 * {@code GET /api/kpi/dashboard}. Materialized-view backing is
 * deferred — the foundation pass computes on-demand from source
 * tables; conversion to materialized views happens in the row-32
 * follow-on once production query load is known.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KpiDashboardDTO(
    UUID hospitalId,
    LocalDate from,
    LocalDate to,
    DoorToDoctor doorToDoctor,
    DispenseLeadTime dispenseLeadTime,
    NoShowRate noShowRate,
    List<KpiTrendPoint> trend
) {
    /**
     * Daily aggregate of the three KPIs for sparkline rendering in the
     * analytics UI. Only populated when the caller requests it
     * ({@code ?withTrends=true} on the controller). Days with zero
     * samples for a given KPI carry a {@code null} value for that field
     * so the sparkline can draw a gap rather than a misleading zero.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KpiTrendPoint(
        LocalDate date,
        Double doorToDoctorAverageMinutes,
        Double dispenseLeadTimeAverageMinutes,
        Double noShowRate
    ) { }

    /**
     * Average minutes between patient arrival and triage completion
     * for encounters in the requested window.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DoorToDoctor(
        long sampleSize,
        Double averageMinutes,
        Double medianMinutesEstimate
    ) {
        public static DoorToDoctor empty() {
            return new DoorToDoctor(0L, null, null);
        }
    }

    /**
     * Average minutes between prescription creation and the first
     * dispense event in the requested window.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DispenseLeadTime(
        long sampleSize,
        Double averageMinutes
    ) {
        public static DispenseLeadTime empty() {
            return new DispenseLeadTime(0L, null);
        }
    }

    /**
     * Ratio of {@code NO_SHOW} appointments to scheduled appointments
     * in the requested window. {@code rate} is expressed as a value
     * in {@code [0.0, 1.0]} — multiply by 100 in the UI.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoShowRate(
        long totalAppointments,
        long noShowCount,
        Double rate
    ) {
        public static NoShowRate empty() {
            return new NoShowRate(0L, 0L, null);
        }
    }
}
