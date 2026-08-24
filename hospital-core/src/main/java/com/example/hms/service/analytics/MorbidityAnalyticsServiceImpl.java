package com.example.hms.service.analytics;

import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO;
import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO.DiagnosisSlice;
import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO.HospitalBreakdown;
import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO.Scope;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientProblemRepository.DiagnosisCount;
import com.example.hms.repository.PatientProblemRepository.HospitalDiagnosisCount;
import com.example.hms.model.Hospital;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Top diagnoses per month, scoped to whoever is asking.
 *
 * <p><strong>Scope is derived, never requested.</strong> There is no
 * {@code hospitalId} parameter anywhere in this path, so a caller has no
 * way to name a tenant they are not in. The decision comes from
 * {@link RoleValidator#requireActiveHospitalId()}, which is the house's
 * audited resolver and already encodes the rules this dashboard needs:
 *
 * <ul>
 *   <li>a real super-admin — per the JWT claim, NOT authorities, which
 *       can be inflated by impersonation — with no explicit
 *       {@code X-Hospital-Id} gets {@code null}, meaning global, so this
 *       dashboard shows the network view;</li>
 *   <li>a super-admin who DID scope with {@code X-Hospital-Id} gets that
 *       hospital, so the chip-scoped view narrows the chart to match
 *       rather than silently ignoring the chip;</li>
 *   <li>everyone else gets their own hospital, or a refusal.</li>
 * </ul>
 *
 * <p>A scoped caller receives an EMPTY breakdown list rather than a
 * partial one: a partial list would confirm that other hospitals exist
 * and hold data.
 *
 * <p>Counts come from {@code clinical.patient_problems} windowed on
 * {@code createdAt} — when THIS hospital recorded the diagnosis — for the
 * same reason the {@code TOP_DIAGNOSES} report uses it: {@code onsetDate}
 * is patient-reported, nullable, and can predate the month by years.
 */
@Service
@RequiredArgsConstructor
public class MorbidityAnalyticsServiceImpl implements MorbidityAnalyticsService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PatientProblemRepository problemRepository;
    private final HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;

    @Override
    @Transactional(readOnly = true)
    public MorbidityDashboardDTO topDiagnoses(YearMonth month, int limit) {
        LocalDateTime fromInclusive = month.atDay(1).atStartOfDay();
        LocalDateTime toExclusive = month.plusMonths(1).atDay(1).atStartOfDay();
        String label = month.format(MONTH_FORMAT);

        // null == "global", the resolver's contract for an unscoped super-admin.
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return hospitalId == null
            ? networkView(label, fromInclusive, toExclusive, limit)
            : hospitalView(hospitalId, label, fromInclusive, toExclusive, limit);
    }

    /* ── Global: every hospital, plus the per-hospital split ────────── */

    private MorbidityDashboardDTO networkView(String label, LocalDateTime from,
                                              LocalDateTime to, int limit) {
        List<DiagnosisSlice> overall = slices(
            problemRepository.countDiagnosesAcrossHospitals(from, to), limit);

        // One flat query grouped in memory — a query per hospital would be
        // N+1 against a table that grows with every recorded problem.
        Map<UUID, List<HospitalDiagnosisCount>> grouped = new LinkedHashMap<>();
        for (HospitalDiagnosisCount row : problemRepository.countDiagnosesByHospital(from, to)) {
            grouped.computeIfAbsent(row.getHospitalId(), id -> new ArrayList<>()).add(row);
        }

        List<HospitalBreakdown> byHospital = new ArrayList<>(grouped.size());
        for (List<HospitalDiagnosisCount> rows : grouped.values()) {
            // The query orders by (hospital, count desc), so one hospital's
            // rows arrive already ranked — take the head for the chart and
            // sum the whole list so the total is honest about the tail.
            long totalRecorded = rows.stream().mapToLong(HospitalDiagnosisCount::getTotal).sum();
            List<DiagnosisSlice> top = rows.stream()
                .limit(limit)
                .map(r -> new DiagnosisSlice(r.getCode(), r.getDisplay(), r.getTotal()))
                .toList();
            byHospital.add(new HospitalBreakdown(
                rows.get(0).getHospitalId(), rows.get(0).getHospitalName(), top, totalRecorded));
        }

        return new MorbidityDashboardDTO(label, Scope.NETWORK, null, overall, byHospital);
    }

    /* ── Scoped: one hospital, empty split ─────────────────────────── */

    private MorbidityDashboardDTO hospitalView(UUID hospitalId, String label, LocalDateTime from,
                                               LocalDateTime to, int limit) {
        List<DiagnosisSlice> overall = slices(
            problemRepository.countDiagnosesRecordedInWindow(hospitalId, from, to), limit);
        String hospitalName = hospitalRepository.findById(hospitalId)
            .map(Hospital::getName)
            .orElse(null);

        return new MorbidityDashboardDTO(
            label, Scope.HOSPITAL, hospitalName, overall, List.of());
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    private static List<DiagnosisSlice> slices(List<DiagnosisCount> rows, int limit) {
        return rows.stream()
            .limit(limit)
            .map(r -> new DiagnosisSlice(r.getCode(), r.getDisplay(), r.getTotal()))
            .toList();
    }
}
