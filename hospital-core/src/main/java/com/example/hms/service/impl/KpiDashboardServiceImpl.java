package com.example.hms.service.impl;

import com.example.hms.payload.dto.analytics.KpiDashboardDTO;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.DispenseLeadTime;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.DoorToDoctor;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.KpiTrendPoint;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.NoShowRate;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.KpiDashboardService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * On-demand computation of the three care-delivery KPIs for the current
 * hospital context (roadmap row 32 foundation + follow-on).
 *
 * <p>Queries are deliberately on-the-fly native SQL against source
 * tables — materialized-view backing is explicitly deferred until
 * production query load is observed (H2 doesn't support MATERIALIZED
 * VIEW; premature materialization burns autovacuum cycles before we
 * know the hot KPI).
 *
 * <p>Read-only routing: {@code @Transactional(readOnly = true)} is
 * load-bearing — when the read replica is enabled
 * ({@code app.datasource.replica.enabled=true}) the queries route to
 * the replica via {@code ReadWriteRoutingDataSource}.
 */
@Service
@Transactional(readOnly = true)
public class KpiDashboardServiceImpl implements KpiDashboardService {

    private static final String PARAM_HOSPITAL_ID = "hospitalId";
    private static final String PARAM_WINDOW_START = "windowStart";
    private static final String PARAM_WINDOW_END = "windowEnd";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public KpiDashboardDTO computeDashboard(LocalDate fromInclusive, LocalDate toInclusive, boolean withTrends) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toInclusive, "toInclusive");
        if (toInclusive.isBefore(fromInclusive)) {
            throw new IllegalArgumentException("toInclusive must be on or after fromInclusive");
        }

        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        // For super-admins the JWT-derived primary hospital is global context, not
        // a scoped view. Only an explicit X-Hospital-Id header (headerOverridden=true)
        // establishes a pinned hospital scope. Without the pin, return an empty rollup.
        UUID hospitalId = (ctx.isSuperAdmin() && !ctx.isHeaderOverridden())
            ? null
            : ctx.getActiveHospitalId();
        if (hospitalId == null) {
            // Super-admin without an explicit hospital pin: return an
            // empty rollup. The dashboard must be opened inside a
            // specific hospital context to render numbers.
            return new KpiDashboardDTO(
                null, fromInclusive, toInclusive,
                DoorToDoctor.empty(),
                DispenseLeadTime.empty(),
                NoShowRate.empty(),
                null
            );
        }

        LocalDateTime windowStart = fromInclusive.atStartOfDay();
        LocalDateTime windowEnd = toInclusive.plusDays(1).atStartOfDay();
        LocalDate appointmentEndExclusive = toInclusive.plusDays(1);

        DoorToDoctor d2d = computeDoorToDoctor(hospitalId, windowStart, windowEnd);
        DispenseLeadTime lead = computeDispenseLeadTime(hospitalId, windowStart, windowEnd);
        NoShowRate noShow = computeNoShowRate(hospitalId, fromInclusive, appointmentEndExclusive);

        List<KpiTrendPoint> trend = withTrends
            ? computeTrend(hospitalId, fromInclusive, toInclusive, windowStart, windowEnd, appointmentEndExclusive)
            : null;

        return new KpiDashboardDTO(hospitalId, fromInclusive, toInclusive, d2d, lead, noShow, trend);
    }

    private DoorToDoctor computeDoorToDoctor(UUID hospitalId, LocalDateTime from, LocalDateTime to) {
        // Encounters whose triage was recorded inside the window. Measures
        // arrival → triage (row 11 / V37 "door-to-doctor" approximation),
        // not arrival → first order. PERCENTILE_CONT(0.5) computes the
        // continuous median, which is more robust than AVG against the
        // long tail of overnight-stay outliers.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COUNT(*) AS sample_size,
                AVG(EXTRACT(EPOCH FROM (e.triage_timestamp - e.arrival_timestamp))) AS avg_seconds,
                PERCENTILE_CONT(0.5) WITHIN GROUP (
                    ORDER BY EXTRACT(EPOCH FROM (e.triage_timestamp - e.arrival_timestamp))
                ) AS median_seconds
            FROM clinical.encounters e
            WHERE e.hospital_id = :hospitalId
              AND e.arrival_timestamp IS NOT NULL
              AND e.triage_timestamp IS NOT NULL
              AND e.triage_timestamp >= :windowStart
              AND e.triage_timestamp <  :windowEnd
              AND e.triage_timestamp >  e.arrival_timestamp
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_WINDOW_START, from)
            .setParameter(PARAM_WINDOW_END, to)
            .getSingleResult();

        long sampleSize = ((Number) row[0]).longValue();
        Double avgSeconds = row[1] == null ? null : ((Number) row[1]).doubleValue();
        Double avgMinutes = avgSeconds == null ? null : avgSeconds / 60.0;
        Double medianSeconds = row[2] == null ? null : ((Number) row[2]).doubleValue();
        Double medianMinutes = medianSeconds == null ? null : medianSeconds / 60.0;
        return new DoorToDoctor(sampleSize, avgMinutes, medianMinutes);
    }

    private DispenseLeadTime computeDispenseLeadTime(UUID hospitalId, LocalDateTime from, LocalDateTime to) {
        // Lead time = dispensed_at minus the parent prescription's
        // created_at (BaseEntity). Tenant scope rides on the prescription
        // side because Dispense has no direct hospital_id column.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COUNT(*) AS sample_size,
                AVG(EXTRACT(EPOCH FROM (d.dispensed_at - p.created_at))) AS avg_seconds
            FROM clinical.dispenses d
            JOIN clinical.prescriptions p ON p.id = d.prescription_id
            WHERE p.hospital_id  = :hospitalId
              AND d.dispensed_at IS NOT NULL
              AND p.created_at   IS NOT NULL
              AND d.dispensed_at >= :windowStart
              AND d.dispensed_at <  :windowEnd
              AND d.dispensed_at >  p.created_at
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_WINDOW_START, from)
            .setParameter(PARAM_WINDOW_END, to)
            .getSingleResult();

        long sampleSize = ((Number) row[0]).longValue();
        Double avgSeconds = row[1] == null ? null : ((Number) row[1]).doubleValue();
        Double avgMinutes = avgSeconds == null ? null : avgSeconds / 60.0;
        return new DispenseLeadTime(sampleSize, avgMinutes);
    }

    private NoShowRate computeNoShowRate(UUID hospitalId, LocalDate fromInclusive, LocalDate toExclusive) {
        // Appointment.appointmentDate is LocalDate, not LocalDateTime — use
        // a LocalDate exclusive-end window to avoid mid-day rounding errors.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COUNT(*)                                                  AS total,
                SUM(CASE WHEN a.status = 'NO_SHOW' THEN 1 ELSE 0 END)     AS no_show
            FROM clinical.appointments a
            WHERE a.hospital_id      = :hospitalId
              AND a.appointment_date >= :fromInclusive
              AND a.appointment_date <  :toExclusive
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter("fromInclusive", fromInclusive)
            .setParameter("toExclusive", toExclusive)
            .getSingleResult();

        long total = ((Number) row[0]).longValue();
        long noShow = row[1] == null ? 0L : ((Number) row[1]).longValue();
        Double rate = total == 0 ? null : (double) noShow / (double) total;
        return new NoShowRate(total, noShow, rate);
    }

    /**
     * Daily timeseries for sparkline rendering. Three separate
     * group-by-day queries (one per KPI) get merged into a single
     * date-indexed list. Days with no samples for a given KPI carry a
     * {@code null} value for that field; the UI draws a gap. Days with
     * no samples for ANY KPI are still emitted as a point (all-null)
     * so the sparkline's x-axis covers the requested window evenly.
     */
    private List<KpiTrendPoint> computeTrend(
        UUID hospitalId,
        LocalDate fromInclusive,
        LocalDate toInclusive,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        LocalDate appointmentEndExclusive
    ) {
        Map<LocalDate, double[]> series = new TreeMap<>();

        // door-to-doctor trend
        @SuppressWarnings("unchecked")
        List<Object[]> d2dRows = entityManager.createNativeQuery("""
            SELECT
                CAST(e.triage_timestamp AS DATE) AS day,
                AVG(EXTRACT(EPOCH FROM (e.triage_timestamp - e.arrival_timestamp))) AS avg_seconds
            FROM clinical.encounters e
            WHERE e.hospital_id = :hospitalId
              AND e.arrival_timestamp IS NOT NULL
              AND e.triage_timestamp IS NOT NULL
              AND e.triage_timestamp >= :windowStart
              AND e.triage_timestamp <  :windowEnd
              AND e.triage_timestamp >  e.arrival_timestamp
            GROUP BY CAST(e.triage_timestamp AS DATE)
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_WINDOW_START, windowStart)
            .setParameter(PARAM_WINDOW_END, windowEnd)
            .getResultList();
        for (Object[] r : d2dRows) {
            LocalDate day = toLocalDate(r[0]);
            Double seconds = r[1] == null ? null : ((Number) r[1]).doubleValue();
            seriesFor(series, day)[0] = seconds == null ? Double.NaN : seconds / 60.0;
        }

        // dispense lead-time trend
        @SuppressWarnings("unchecked")
        List<Object[]> leadRows = entityManager.createNativeQuery("""
            SELECT
                CAST(d.dispensed_at AS DATE) AS day,
                AVG(EXTRACT(EPOCH FROM (d.dispensed_at - p.created_at))) AS avg_seconds
            FROM clinical.dispenses d
            JOIN clinical.prescriptions p ON p.id = d.prescription_id
            WHERE p.hospital_id  = :hospitalId
              AND d.dispensed_at IS NOT NULL
              AND p.created_at   IS NOT NULL
              AND d.dispensed_at >= :windowStart
              AND d.dispensed_at <  :windowEnd
              AND d.dispensed_at >  p.created_at
            GROUP BY CAST(d.dispensed_at AS DATE)
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_WINDOW_START, windowStart)
            .setParameter(PARAM_WINDOW_END, windowEnd)
            .getResultList();
        for (Object[] r : leadRows) {
            LocalDate day = toLocalDate(r[0]);
            Double seconds = r[1] == null ? null : ((Number) r[1]).doubleValue();
            seriesFor(series, day)[1] = seconds == null ? Double.NaN : seconds / 60.0;
        }

        // no-show rate trend (rate per day = noShow / total)
        @SuppressWarnings("unchecked")
        List<Object[]> noShowRows = entityManager.createNativeQuery("""
            SELECT
                a.appointment_date                                       AS day,
                COUNT(*)                                                 AS total,
                SUM(CASE WHEN a.status = 'NO_SHOW' THEN 1 ELSE 0 END)    AS no_show
            FROM clinical.appointments a
            WHERE a.hospital_id      = :hospitalId
              AND a.appointment_date >= :fromInclusive
              AND a.appointment_date <  :toExclusive
            GROUP BY a.appointment_date
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter("fromInclusive", fromInclusive)
            .setParameter("toExclusive", appointmentEndExclusive)
            .getResultList();
        for (Object[] r : noShowRows) {
            LocalDate day = toLocalDate(r[0]);
            long total = ((Number) r[1]).longValue();
            long noShow = r[2] == null ? 0L : ((Number) r[2]).longValue();
            double rate = total == 0 ? Double.NaN : (double) noShow / (double) total;
            seriesFor(series, day)[2] = rate;
        }

        // Emit the day-by-day trend in window order. Days with no data
        // for any KPI are skipped (a sparkline of all-nulls is just noise).
        List<KpiTrendPoint> out = new ArrayList<>(series.size());
        for (Map.Entry<LocalDate, double[]> e : series.entrySet()) {
            double[] v = e.getValue();
            // Guard window bounds — a CAST-to-DATE on a timestamp can
            // edge a row outside the requested [from, to] when the
            // database is in a non-UTC zone. Drop those rather than
            // pollute the sparkline.
            LocalDate day = e.getKey();
            if (day.isBefore(fromInclusive) || day.isAfter(toInclusive)) continue;
            out.add(new KpiTrendPoint(
                day,
                Double.isNaN(v[0]) ? null : v[0],
                Double.isNaN(v[1]) ? null : v[1],
                Double.isNaN(v[2]) ? null : v[2]
            ));
        }
        return out;
    }

    private static double[] seriesFor(Map<LocalDate, double[]> series, LocalDate day) {
        return series.computeIfAbsent(day, d -> new double[]{Double.NaN, Double.NaN, Double.NaN});
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            throw new IllegalStateException("trend group-by day was null");
        }
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof java.util.Date utilDate) {
            return new Date(utilDate.getTime()).toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }
}
