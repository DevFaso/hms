package com.example.hms.service.impl;

import com.example.hms.analytics.KpiMaterializedViewProperties;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.DispenseLeadTime;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.DoorToDoctor;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.KpiTrendPoint;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.NoShowRate;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.KpiDashboardService;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final Logger log = LoggerFactory.getLogger(KpiDashboardServiceImpl.class);

    private static final String PARAM_HOSPITAL_ID = "hospitalId";
    private static final String PARAM_WINDOW_START = "windowStart";
    private static final String PARAM_WINDOW_END = "windowEnd";
    private static final String PARAM_FROM_INCLUSIVE = "fromInclusive";
    private static final String PARAM_TO_EXCLUSIVE = "toExclusive";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Optional matview tier (row 32 follow-on V105). When enabled, the
     * three KPI queries read from pre-aggregated matviews
     * {@code clinical.kpi_*_daily}; when disabled (the default), the
     * original on-the-fly native SQL runs against source tables
     * exactly as the foundation pass shipped. Resolved through an
     * {@link ObjectProvider} so the existing H2-backed test suite
     * that doesn't load {@link KpiMaterializedViewProperties}
     * continues to work — and Sonar's S6813 (no field injection)
     * is satisfied because the dependency arrives via the
     * constructor.
     */
    private KpiMaterializedViewProperties matviewProperties;

    /**
     * Cached at startup: true when the three matview relations
     * actually exist in the connected database. Lets {@link #useMatviews()}
     * fall back to the on-the-fly path when the flag is enabled but
     * V105 hasn't been applied (e.g. an environment that flipped
     * the flag pre-migration, or an H2-backed deployment where the
     * V105 preCondition skipped the changeset).
     */
    private volatile boolean matviewsPresent;

    public KpiDashboardServiceImpl(ObjectProvider<KpiMaterializedViewProperties> matviewPropertiesProvider) {
        this.matviewProperties = matviewPropertiesProvider.getIfAvailable();
    }

    @PostConstruct
    void detectMatviewPresence() {
        if (matviewProperties == null || !matviewProperties.isEnabled()) {
            this.matviewsPresent = false;
            return;
        }
        try {
            Number found = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'clinical'
                  AND table_name IN (
                    'kpi_door_to_doctor_daily',
                    'kpi_dispense_lead_time_daily',
                    'kpi_no_show_rate_daily'
                  )
                """).getSingleResult();
            this.matviewsPresent = found != null && found.intValue() == 3;
            if (!this.matviewsPresent) {
                log.info("KPI matview flag is on but the three clinical.kpi_*_daily relations were "
                    + "not all found in information_schema — falling back to on-the-fly queries");
            }
        } catch (RuntimeException ex) {
            // information_schema is portable to PG/H2; any failure here
            // means we cannot prove the matviews exist, so degrade
            // safely to the on-the-fly path rather than letting the
            // first dashboard request 500.
            log.warn("KPI matview presence check failed — falling back to on-the-fly queries: {}",
                ex.toString());
            this.matviewsPresent = false;
        }
    }

    private boolean useMatviews() {
        return matviewProperties != null && matviewProperties.isEnabled() && matviewsPresent;
    }

    @Override
    public KpiDashboardDTO computeDashboard(LocalDate fromInclusive, LocalDate toInclusive, boolean withTrends) {
        Objects.requireNonNull(fromInclusive, PARAM_FROM_INCLUSIVE);
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
        if (useMatviews()) {
            return computeDoorToDoctorFromMatview(hospitalId, from.toLocalDate(), to.toLocalDate());
        }
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
        if (useMatviews()) {
            return computeDispenseLeadTimeFromMatview(hospitalId, from.toLocalDate(), to.toLocalDate());
        }
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
        if (useMatviews()) {
            return computeNoShowRateFromMatview(hospitalId, fromInclusive, toExclusive);
        }
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
            .setParameter(PARAM_FROM_INCLUSIVE, fromInclusive)
            .setParameter(PARAM_TO_EXCLUSIVE, toExclusive)
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
     * {@code null} value for that field; the UI draws a gap.
     *
     * <p>Implemented as orchestrator + three per-KPI helpers so each
     * helper stays under SonarQube's cognitive-complexity limit of 15
     * (the inlined version triggered Critical "Refactor this method
     * to reduce its Cognitive Complexity from 25 to the 15 allowed"
     * on PR #357).
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
        addDoorToDoctorTrend(series, hospitalId, windowStart, windowEnd);
        addDispenseLeadTimeTrend(series, hospitalId, windowStart, windowEnd);
        addNoShowRateTrend(series, hospitalId, fromInclusive, appointmentEndExclusive);
        return emitTrendPoints(series, fromInclusive, toInclusive);
    }

    private void addDoorToDoctorTrend(
        Map<LocalDate, double[]> series, UUID hospitalId,
        LocalDateTime windowStart, LocalDateTime windowEnd
    ) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
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
        for (Object[] r : rows) {
            Double seconds = r[1] == null ? null : ((Number) r[1]).doubleValue();
            seriesFor(series, toLocalDate(r[0]))[0] =
                seconds == null ? Double.NaN : seconds / 60.0;
        }
    }

    private void addDispenseLeadTimeTrend(
        Map<LocalDate, double[]> series, UUID hospitalId,
        LocalDateTime windowStart, LocalDateTime windowEnd
    ) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
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
        for (Object[] r : rows) {
            Double seconds = r[1] == null ? null : ((Number) r[1]).doubleValue();
            seriesFor(series, toLocalDate(r[0]))[1] =
                seconds == null ? Double.NaN : seconds / 60.0;
        }
    }

    private void addNoShowRateTrend(
        Map<LocalDate, double[]> series, UUID hospitalId,
        LocalDate fromInclusive, LocalDate appointmentEndExclusive
    ) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
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
            .setParameter(PARAM_FROM_INCLUSIVE, fromInclusive)
            .setParameter(PARAM_TO_EXCLUSIVE, appointmentEndExclusive)
            .getResultList();
        for (Object[] r : rows) {
            long total = ((Number) r[1]).longValue();
            long noShow = r[2] == null ? 0L : ((Number) r[2]).longValue();
            seriesFor(series, toLocalDate(r[0]))[2] =
                total == 0 ? Double.NaN : (double) noShow / (double) total;
        }
    }

    /**
     * Emit the day-by-day trend in window order. Drops days that
     * CAST-to-DATE pushed outside the requested {@code [from, to]}
     * window (which can happen in a non-UTC database). NaN values
     * for any KPI on a given day surface as {@code null} so the UI
     * sparkline draws a gap.
     */
    private static List<KpiTrendPoint> emitTrendPoints(
        Map<LocalDate, double[]> series,
        LocalDate fromInclusive, LocalDate toInclusive
    ) {
        List<KpiTrendPoint> out = new ArrayList<>(series.size());
        for (Map.Entry<LocalDate, double[]> e : series.entrySet()) {
            LocalDate day = e.getKey();
            if (day.isBefore(fromInclusive) || day.isAfter(toInclusive)) continue;
            double[] v = e.getValue();
            out.add(new KpiTrendPoint(
                day,
                Double.isNaN(v[0]) ? null : v[0],
                Double.isNaN(v[1]) ? null : v[1],
                Double.isNaN(v[2]) ? null : v[2]
            ));
        }
        return out;
    }

    // ── Matview-backed query path (row 32 follow-on, V105) ───────────────
    //
    // When app.analytics.kpi.materialized-views.enabled=true the three
    // compute* methods route here instead of running the on-the-fly
    // aggregations against source tables. The matview queries are
    // SUM / weighted-AVG rollups over per-day pre-aggregates — single
    // index lookup on (hospital_id, day_bucket) rather than a full
    // hospital-scoped scan of clinical.encounters / dispenses /
    // appointments.

    private DoorToDoctor computeDoorToDoctorFromMatview(
        UUID hospitalId, LocalDate fromInclusive, LocalDate toExclusive
    ) {
        // Weighted average across daily buckets: SUM(sample_size * avg) /
        // SUM(sample_size). Median across days isn't recoverable from a
        // per-day AVG; the matview path returns the AVG-of-AVG as the
        // headline median proxy. Operators reading the headline get the
        // central-tendency signal; the precise PERCENTILE_CONT(0.5) is
        // only available via the on-the-fly path (matview-disabled).
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COALESCE(SUM(sample_size), 0) AS sample_size,
                CASE WHEN SUM(sample_size) > 0
                    THEN SUM(sample_size * avg_seconds) / SUM(sample_size)
                    ELSE NULL END             AS avg_seconds,
                AVG(median_seconds)           AS median_seconds_proxy
            FROM clinical.kpi_door_to_doctor_daily
            WHERE hospital_id = :hospitalId
              AND day_bucket >= :fromInclusive
              AND day_bucket <  :toExclusive
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_FROM_INCLUSIVE, fromInclusive)
            .setParameter(PARAM_TO_EXCLUSIVE, toExclusive)
            .getSingleResult();
        long sampleSize = ((Number) row[0]).longValue();
        Double avgSeconds = row[1] == null ? null : ((Number) row[1]).doubleValue();
        Double avgMinutes = avgSeconds == null ? null : avgSeconds / 60.0;
        Double medianSeconds = row[2] == null ? null : ((Number) row[2]).doubleValue();
        Double medianMinutes = medianSeconds == null ? null : medianSeconds / 60.0;
        return new DoorToDoctor(sampleSize, avgMinutes, medianMinutes);
    }

    private DispenseLeadTime computeDispenseLeadTimeFromMatview(
        UUID hospitalId, LocalDate fromInclusive, LocalDate toExclusive
    ) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COALESCE(SUM(sample_size), 0) AS sample_size,
                CASE WHEN SUM(sample_size) > 0
                    THEN SUM(sample_size * avg_seconds) / SUM(sample_size)
                    ELSE NULL END             AS avg_seconds
            FROM clinical.kpi_dispense_lead_time_daily
            WHERE hospital_id = :hospitalId
              AND day_bucket >= :fromInclusive
              AND day_bucket <  :toExclusive
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_FROM_INCLUSIVE, fromInclusive)
            .setParameter(PARAM_TO_EXCLUSIVE, toExclusive)
            .getSingleResult();
        long sampleSize = ((Number) row[0]).longValue();
        Double avgSeconds = row[1] == null ? null : ((Number) row[1]).doubleValue();
        Double avgMinutes = avgSeconds == null ? null : avgSeconds / 60.0;
        return new DispenseLeadTime(sampleSize, avgMinutes);
    }

    private NoShowRate computeNoShowRateFromMatview(
        UUID hospitalId, LocalDate fromInclusive, LocalDate toExclusive
    ) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COALESCE(SUM(total_count), 0)   AS total,
                COALESCE(SUM(no_show_count), 0) AS no_show
            FROM clinical.kpi_no_show_rate_daily
            WHERE hospital_id = :hospitalId
              AND day_bucket >= :fromInclusive
              AND day_bucket <  :toExclusive
            """)
            .setParameter(PARAM_HOSPITAL_ID, hospitalId)
            .setParameter(PARAM_FROM_INCLUSIVE, fromInclusive)
            .setParameter(PARAM_TO_EXCLUSIVE, toExclusive)
            .getSingleResult();
        long total = ((Number) row[0]).longValue();
        long noShow = row[1] == null ? 0L : ((Number) row[1]).longValue();
        Double rate = total == 0 ? null : (double) noShow / (double) total;
        return new NoShowRate(total, noShow, rate);
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
