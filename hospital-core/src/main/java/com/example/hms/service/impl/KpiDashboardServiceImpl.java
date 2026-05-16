package com.example.hms.service.impl;

import com.example.hms.payload.dto.analytics.KpiDashboardDTO;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.DispenseLeadTime;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.DoorToDoctor;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.NoShowRate;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.KpiDashboardService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * On-demand computation of the three care-delivery KPIs for the current
 * hospital context (roadmap row 32 foundation pass).
 *
 * <p>Queries are deliberately on-the-fly native SQL against source
 * tables — the row-32 follow-on converts the hot two (door-to-doctor +
 * dispense lead time) into PostgreSQL {@code MATERIALIZED VIEW}s with
 * a {@code @Scheduled} refresh once production query load is observed.
 * The conversion is a target-table swap inside each {@code computeX}
 * method — the DTO contract stays stable.
 *
 * <p>Read-only routing: {@code @Transactional(readOnly = true)} is
 * load-bearing — when the read replica is enabled
 * ({@code app.datasource.replica.enabled=true}) the queries route to
 * the replica via {@code ReadWriteRoutingDataSource}.
 */
@Service
@Transactional(readOnly = true)
public class KpiDashboardServiceImpl implements KpiDashboardService {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public KpiDashboardDTO computeDashboard(LocalDate fromInclusive, LocalDate toInclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toInclusive, "toInclusive");
        if (toInclusive.isBefore(fromInclusive)) {
            throw new IllegalArgumentException("toInclusive must be on or after fromInclusive");
        }

        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID hospitalId = ctx.getActiveHospitalId();
        if (hospitalId == null) {
            // Super-admin without an explicit hospital pin: return an
            // empty rollup. The dashboard must be opened inside a
            // specific hospital context to render numbers.
            return new KpiDashboardDTO(
                null, fromInclusive, toInclusive,
                DoorToDoctor.empty(),
                DispenseLeadTime.empty(),
                NoShowRate.empty()
            );
        }

        LocalDateTime windowStart = fromInclusive.atStartOfDay();
        LocalDateTime windowEnd = toInclusive.plusDays(1).atStartOfDay();
        LocalDate appointmentEndExclusive = toInclusive.plusDays(1);

        DoorToDoctor d2d = computeDoorToDoctor(hospitalId, windowStart, windowEnd);
        DispenseLeadTime lead = computeDispenseLeadTime(hospitalId, windowStart, windowEnd);
        NoShowRate noShow = computeNoShowRate(hospitalId, fromInclusive, appointmentEndExclusive);

        return new KpiDashboardDTO(hospitalId, fromInclusive, toInclusive, d2d, lead, noShow);
    }

    private DoorToDoctor computeDoorToDoctor(UUID hospitalId, LocalDateTime from, LocalDateTime to) {
        // Encounters whose triage was recorded inside the window. Measures
        // arrival → triage (row 11 / V37 "door-to-doctor" approximation),
        // not arrival → first order. Native SQL because EXTRACT(EPOCH FROM …)
        // is the simplest cross-DB timestamp-diff and both PG and H2-PG-mode
        // honor it.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            SELECT
                COUNT(*) AS sample_size,
                AVG(EXTRACT(EPOCH FROM (e.triage_timestamp - e.arrival_timestamp))) AS avg_seconds
            FROM clinical.encounters e
            WHERE e.hospital_id = :hospitalId
              AND e.arrival_timestamp IS NOT NULL
              AND e.triage_timestamp IS NOT NULL
              AND e.triage_timestamp >= :windowStart
              AND e.triage_timestamp <  :windowEnd
              AND e.triage_timestamp >  e.arrival_timestamp
            """)
            .setParameter("hospitalId", hospitalId)
            .setParameter("windowStart", from)
            .setParameter("windowEnd", to)
            .getSingleResult();

        long sampleSize = ((Number) row[0]).longValue();
        Double avgSeconds = row[1] == null ? null : ((Number) row[1]).doubleValue();
        Double avgMinutes = avgSeconds == null ? null : avgSeconds / 60.0;
        return new DoorToDoctor(sampleSize, avgMinutes, null);
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
            .setParameter("hospitalId", hospitalId)
            .setParameter("windowStart", from)
            .setParameter("windowEnd", to)
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
            .setParameter("hospitalId", hospitalId)
            .setParameter("fromInclusive", fromInclusive)
            .setParameter("toExclusive", toExclusive)
            .getSingleResult();

        long total = ((Number) row[0]).longValue();
        long noShow = row[1] == null ? 0L : ((Number) row[1]).longValue();
        Double rate = total == 0 ? null : (double) noShow / (double) total;
        return new NoShowRate(total, noShow, rate);
    }
}
