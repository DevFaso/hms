package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.OrganisationSummaryDTO;
import com.example.hms.payload.dto.OrganisationSummaryDTO.HospitalTotals;
import com.example.hms.payload.dto.OrganisationSummaryDTO.OrganisationHospital;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.OrganisationTotalsRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.SmallCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Platform admin rules 2026-11-20, rules 1a and 1b: what an organisation
 * admin ({@code ROLE_ADMIN}) reads about its own organisation.
 *
 * <p>The scope is {@link UserAccountAccess#organisationHospitalIds}: the
 * hospitals that share an organisation with a hospital where the caller
 * holds an ACTIVE ADMIN assignment. It is computed from the caller's own
 * assignments on every call; no request field can widen it, and a caller
 * with no such assignment is refused before any figure is read.
 *
 * <p>The figures are totals only: activity counts and finance totals per
 * hospital and per period. Nothing here returns a patient, an invoice or a
 * note, and a small count in a sensitive category is withheld
 * ({@link SmallCounts}).
 */
@Service
@RequiredArgsConstructor
public class OrganisationAdminService {

    /** The longest period one summary covers. */
    static final int MAX_PERIOD_DAYS = 366;

    private final UserAccountAccess accountAccess;
    private final OrganisationTotalsRepository totals;
    private final StaffRepository staffRepository;
    private final AdmissionRepository admissionRepository;
    private final Clock clock;

    /** The hospitals of the caller's organisation, for the register form's picker. */
    @Transactional(readOnly = true)
    public List<OrganisationHospital> hospitals() {
        return scopedHospitals().stream()
            .map(h -> new OrganisationHospital(h.getId(), h.getName(), h.getCity(), h.getCode(),
                h.getCurrencyCode(), h.getLifecycleState() != null ? h.getLifecycleState().name() : null))
            .toList();
    }

    /**
     * Totals per hospital for a period. With no dates: the current month to
     * date. The end is never before the start, and the period is at most a year.
     */
    @Transactional(readOnly = true)
    public OrganisationSummaryDTO summary(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate last = to != null ? to : today;
        LocalDate first = from != null ? from : last.withDayOfMonth(1);
        if (last.isBefore(first) || first.plusDays(MAX_PERIOD_DAYS).isBefore(last)) {
            throw new FieldBusinessException("to", "organisation.summary.period.invalid");
        }
        List<HospitalTotals> rows = scopedHospitals().stream()
            .map(hospital -> totalsOf(hospital, first, last))
            .toList();
        return new OrganisationSummaryDTO(first, last, SmallCounts.THRESHOLD, rows);
    }

    private HospitalTotals totalsOf(Hospital hospital, LocalDate first, LocalDate last) {
        UUID id = hospital.getId();
        Long admissions = admissionRepository.countAdmissionsByHospitalAndDateRange(
            id, first.atStartOfDay(), last.plusDays(1).atStartOfDay().minusNanos(1));
        return new HospitalTotals(id, hospital.getName(), hospital.getCurrencyCode(),
            staffRepository.countActiveByHospitalId(id),
            totals.countAppointments(id, first, last),
            admissions != null ? admissions : 0L,
            totals.sumInvoiced(id, first, last),
            totals.sumCollected(id, first, last),
            SmallCounts.of(totals.countDeaths(id, first.atStartOfDay(), last.plusDays(1).atStartOfDay())));
    }

    /** The caller's organisation hospitals, or a refusal for a caller who administers no organisation. */
    private List<Hospital> scopedHospitals() {
        Set<UUID> ids = accountAccess.organisationHospitalIds();
        if (ids.isEmpty()) {
            throw new AccessDeniedException("Access denied");
        }
        return totals.findClinicalByIdIn(ids);
    }
}
