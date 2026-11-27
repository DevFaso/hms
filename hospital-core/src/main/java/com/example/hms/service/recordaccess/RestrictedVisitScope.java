package com.example.hms.service.recordaccess;

import com.example.hms.model.Encounter;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.model.referral.ObgynReferral;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Content tied to a restricted visit follows the visit's restriction in every
 * shared view.
 *
 * <p>The cross-hospital rule ({@link CrossHospitalRows}, decision D3) is
 * written for a row that carries a category. Most of what a visit produces
 * carries none of its own: a prescription, a lab order and its results, an
 * imaging order and its report, a procedure order, a discharge summary, a
 * vaccination, a referral, a dispensing. Each of them is judged by the visit
 * it was recorded in. This class is that judgement for one read: it holds the
 * patient's visits another hospital recorded that the rule withholds from the
 * acting hospital ({@link RecordAccessPolicy#withheldVisits}), and answers,
 * type by type, whether a row may be shown.
 *
 * <p>The acting hospital's own visits are never withheld (the rule is about
 * what crosses a hospital boundary), and a live break-the-glass session
 * unlocks the visits and what hangs off them together: in both cases the
 * visit is simply not in the scope. A row is judged by its visit, not by the
 * hospital that holds it, with one exception:
 * <ul>
 *   <li>a dispensing the acting hospital handed out is always shown there
 *       ({@link #of(Collection, UUID)}): its clinicians must see a drug they
 *       gave, even when the prescription it fills is withheld;</li>
 *   <li>a lab order or result the acting hospital performed for another
 *       hospital's withheld visit stays withheld: it is part of that visit's
 *       record, and the performing lab works it through its own queues, not
 *       through the patient's shared views.</li>
 * </ul>
 *
 * <p>A vital sign names no visit. It is withheld when it was taken at the
 * hospital of a withheld visit on a day that visit covers, the rule
 * {@link ProxyRecordFilter} already applies for a family reader.
 *
 * <p>One instance per read, built by {@link #of}; immutable.
 * {@code RestrictedVisitCoverageGuardTest} fails when a record type that
 * hangs off a visit, or a shared read, is not covered.
 */
public final class RestrictedVisitScope {

    /** Matches no row: what a query is given when nothing is withheld, since an empty {@code NOT IN} is not portable. */
    private static final UUID NO_VISIT = new UUID(0L, 0L);

    private static final RestrictedVisitScope NONE = new RestrictedVisitScope(List.of(), null);

    private final List<Encounter> visits;
    private final Set<UUID> visitIds;
    /** The hospital the read is made at, when known: what it dispensed itself is never withheld from it. */
    private final UUID actingHospitalId;

    private RestrictedVisitScope(List<Encounter> visits, UUID actingHospitalId) {
        this.visits = List.copyOf(visits);
        this.actingHospitalId = actingHospitalId;
        Set<UUID> ids = new HashSet<>();
        for (Encounter visit : visits) {
            if (visit.getId() != null) {
                ids.add(visit.getId());
            }
        }
        this.visitIds = Set.copyOf(ids);
    }

    /** Nothing withheld: a read with no acting hospital, or one confined to the acting hospital. */
    public static RestrictedVisitScope none() {
        return NONE;
    }

    /** @param withheldVisits the answer of {@link RecordAccessPolicy#withheldVisits} for this read */
    public static RestrictedVisitScope of(Collection<Encounter> withheldVisits) {
        return of(withheldVisits, null);
    }

    /**
     * @param withheldVisits   the answer of {@link RecordAccessPolicy#withheldVisits} for this read
     * @param actingHospitalId the hospital the read is made at: a dispensing it handed out is shown
     */
    public static RestrictedVisitScope of(Collection<Encounter> withheldVisits, UUID actingHospitalId) {
        if (withheldVisits == null || withheldVisits.isEmpty()) {
            return NONE;
        }
        return new RestrictedVisitScope(withheldVisits.stream().filter(Objects::nonNull).toList(), actingHospitalId);
    }

    public boolean isEmpty() {
        return visitIds.isEmpty();
    }

    /** The withheld visits' ids, for a query's {@code NOT IN}; never empty. */
    public Set<UUID> visitIdsForQuery() {
        return visitIds.isEmpty() ? Set.of(NO_VISIT) : visitIds;
    }

    /** A row is shown when it names no visit, or a visit that is not withheld. */
    public boolean admitsVisit(UUID encounterId) {
        return encounterId == null || !visitIds.contains(encounterId);
    }

    public boolean admits(Encounter visit) {
        return visit == null || admitsVisit(visit.getId());
    }

    public boolean admits(Prescription prescription) {
        return prescription == null || admits(prescription.getEncounter());
    }

    /**
     * A dispensing follows the prescription it fills; one with no prescription
     * names no visit. One the acting hospital handed out is always shown there.
     */
    public boolean admits(PharmacyFill fill) {
        if (fill == null) {
            return true;
        }
        if (actingHospitalId != null && fill.getHospital() != null
                && actingHospitalId.equals(fill.getHospital().getId())) {
            return true;
        }
        return admits(fill.getPrescription());
    }

    public boolean admits(LabOrder order) {
        return order == null || admits(order.getEncounter());
    }

    /** A result follows its order. */
    public boolean admits(LabResult result) {
        return result == null || admits(result.getLabOrder());
    }

    public boolean admits(ImagingOrder order) {
        return order == null || admitsVisit(order.getEncounterId());
    }

    /** A report follows its order. */
    public boolean admits(ImagingReport report) {
        return report == null || admits(report.getImagingOrder());
    }

    public boolean admits(ProcedureOrder order) {
        return order == null || admits(order.getEncounter());
    }

    public boolean admits(DischargeSummary summary) {
        return summary == null || admits(summary.getEncounter());
    }

    public boolean admits(PatientImmunization immunization) {
        return immunization == null || admits(immunization.getEncounter());
    }

    public boolean admits(ObgynReferral referral) {
        return referral == null || admits(referral.getEncounter());
    }

    /**
     * A vital sign taken at the hospital of a withheld visit, on a day that
     * visit covers (its first day to its checkout day; to today for a visit
     * still open). One with no time cannot be placed: it is withheld when a
     * withheld visit exists at its hospital.
     */
    public boolean admits(PatientVitalSign vital) {
        if (vital == null || visits.isEmpty()) {
            return true;
        }
        UUID vitalHospitalId = vital.getHospital() == null ? null : vital.getHospital().getId();
        LocalDateTime recordedAt = vital.getRecordedAt();
        LocalDate day = recordedAt == null ? null : recordedAt.toLocalDate();
        for (Encounter visit : visits) {
            if (sameHospital(vitalHospitalId, visit) && (day == null || covers(visit, day))) {
                return false;
            }
        }
        return true;
    }

    static boolean sameHospital(UUID rowHospitalId, Encounter visit) {
        UUID visitHospitalId = visit.getHospital() == null ? null : visit.getHospital().getId();
        // An unknown hospital on either side cannot rule the visit out.
        return rowHospitalId == null || visitHospitalId == null || rowHospitalId.equals(visitHospitalId);
    }

    static boolean covers(Encounter visit, LocalDate day) {
        if (visit.getEncounterDate() == null) {
            return true;
        }
        LocalDate first = visit.getEncounterDate().toLocalDate();
        LocalDate last;
        if (visit.getCheckoutTimestamp() != null) {
            last = visit.getCheckoutTimestamp().toLocalDate();
        } else if (visit.getStatus() != null && visit.getStatus().isClosed()) {
            last = first;
        } else {
            last = LocalDate.now(ZoneId.systemDefault());
        }
        LocalDate end = last.isBefore(first) ? first : last;
        return !day.isBefore(first) && !day.isAfter(end);
    }
}
