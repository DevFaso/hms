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
import java.time.YearMonth;
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

    /** How far back a month may be asked for. */
    static final int MAX_MONTHS_BACK = 24;

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
     * Totals per hospital for ONE calendar month: the month asked for, whole,
     * or with none the current month to date.
     *
     * <p>Never an arbitrary range (review of #1246). With free dates a reader
     * subtracts two overlapping ranges and gets the count of a single day, or
     * walks a range around a withheld count until it is given: the small-count
     * rule would hide nothing. Whole months do not overlap, so two answers
     * never differ by less than a month; the current month is the one period
     * that grows, a day at a time, and its sensitive counts stay under the
     * same rule.
     */
    @Transactional(readOnly = true)
    public OrganisationSummaryDTO summary(YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        YearMonth current = YearMonth.from(today);
        YearMonth asked = month != null ? month : current;
        if (asked.isAfter(current) || asked.isBefore(current.minusMonths(MAX_MONTHS_BACK))) {
            throw new FieldBusinessException("month", "organisation.summary.period.invalid");
        }
        LocalDate first = asked.atDay(1);
        LocalDate last = asked.equals(current) ? today : asked.atEndOfMonth();
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
