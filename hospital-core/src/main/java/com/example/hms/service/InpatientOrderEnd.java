package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Prescription;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * QA D136: the one rule, shared by the MAR and the patient's medication list,
 * for when an inpatient order ended with its stay. An order is a stay's order
 * when it is filed on the stay's own encounter (V193) and that encounter is
 * closed, or, whoever wrote it, when it was written at the stay's hospital
 * between the admission and the discharge of a stay that has since ended.
 */
public final class InpatientOrderEnd {

    private InpatientOrderEnd() {
    }

    public static boolean endedWithAStay(Prescription rx, List<Admission> stays) {
        // QA D757: an order marked for home outlives the stay it was written on.
        if (rx.isTakeHome()) {
            return false;
        }
        if (endedWithItsStay(rx)) {
            return true;
        }
        Encounter encounter = rx.getEncounter();
        // QA D316: an order written on an outpatient visit is the visit's,
        // not a stay's, even when a stay at the same hospital happened to
        // span that moment (a same-day admission and discharge ended every
        // prescription the patient was given that day). The time window
        // below is for orders that name no encounter, and for INPATIENT
        // encounters with no admission link (V193 did not backfill
        // admission_id, so an older stay's encounter carries none).
        if (encounter != null && encounter.getAdmissionId() == null
            && encounter.getEncounterType() != EncounterType.INPATIENT) {
            return false;
        }
        LocalDateTime written = rx.getCreatedAt();
        if (written == null || stays == null) return false;
        for (Admission stay : stays) {
            LocalDateTime from = stay.getAdmissionDateTime();
            LocalDateTime to = stay.getActualDischargeDateTime();
            if (from == null || to == null || written.isBefore(from) || written.isAfter(to)) continue;
            if (sameHospital(rx, stay)) return true;
        }
        return false;
    }

    /**
     * QA D757: the order is filed on a stay's own encounter (V193), that stay
     * has ended, and the order is not marked for home. Such an order is not
     * dispensed, verified or queued any more: the patient has left the ward.
     * Needs no other row, so the pharmacy can apply it on the order alone.
     */
    public static boolean endedWithItsStay(Prescription rx) {
        if (rx == null || rx.isTakeHome()) {
            return false;
        }
        Encounter encounter = rx.getEncounter();
        return encounter != null && encounter.getAdmissionId() != null
            && (encounter.getStatus() == EncounterStatus.COMPLETED
                || encounter.getStatus() == EncounterStatus.CANCELLED);
    }

    /**
     * QA D792 (#953 review): the rule migration V233 ends an order by, for a
     * caller that has the patient's stays at the order's hospital. Wider than
     * {@link #endedWithItsStay}, which needs no other row but sees only a
     * closed stay encounter: an order left live by a stay that ended before
     * the discharge ended its orders (QA D757), or one V233 held for its
     * prepared fill or open partner routing, can have any shape the discharge
     * rule ({@link #orderOfStay}) ends, and none of them may be handed over,
     * verified, claimed, routed or sent by SMS. Not take-home, and one of:
     * <ol>
     *   <li>filed on a stay's own encounter (V193) that is closed, or whose
     *       admission is DISCHARGED or CANCELLED;</li>
     *   <li>filed on no encounter, or on an INPATIENT encounter with no
     *       admission link, and written at the order's hospital between the
     *       admission and the discharge of a stay that has ended;</li>
     *   <li>filed on an EMERGENCY encounter with no admission link that was
     *       still open at the admission of a stay that has ended, written at
     *       most 24 hours before that admission and before its discharge.</li>
     * </ol>
     */
    public static boolean endedWithAnEndedStay(Prescription rx, List<Admission> stays) {
        if (rx == null || rx.isTakeHome()) {
            return false;
        }
        if (endedWithItsStay(rx)) {
            return true;
        }
        if (stays == null || stays.isEmpty()) {
            return false;
        }
        Encounter encounter = rx.getEncounter();
        if (encounter != null && encounter.getAdmissionId() != null) {
            for (Admission stay : stays) {
                if (stay != null && encounter.getAdmissionId().equals(stay.getId())
                    && sameHospital(rx, stay)
                    && (stay.getStatus() == AdmissionStatus.DISCHARGED
                        || stay.getStatus() == AdmissionStatus.CANCELLED)) {
                    return true;
                }
            }
            return false;
        }
        boolean emergency = encounter != null && encounter.getEncounterType() == EncounterType.EMERGENCY;
        if (encounter != null && !emergency && encounter.getEncounterType() != EncounterType.INPATIENT) {
            return false;
        }
        LocalDateTime written = rx.getCreatedAt();
        if (written == null) {
            return false;
        }
        for (Admission stay : stays) {
            if (stay == null || !sameHospital(rx, stay)) continue;
            LocalDateTime from = stay.getAdmissionDateTime();
            LocalDateTime to = stay.getActualDischargeDateTime();
            if (from == null || to == null || written.isAfter(to)) continue;
            if (!emergency) {
                if (!written.isBefore(from)) return true;
                continue;
            }
            LocalDateTime checkout = encounter.getCheckoutTimestamp();
            boolean openAtAdmission = checkout == null || !checkout.isBefore(from);
            if (openAtAdmission && !written.isBefore(from.minus(ED_TO_WARD_CARRY_OVER))) return true;
        }
        return false;
    }

    /** How long before the admission an emergency-department order still belongs to the stay (QA D702/D712). */
    private static final Duration ED_TO_WARD_CARRY_OVER = Duration.ofHours(24);

    /**
     * QA D702: D514 only asked whether the patient is in a bed, so once a
     * patient was admitted every live prescription he had, including the
     * take-home orders of an earlier consultation, became a due dose. A dose
     * is due on the ward only for an order of the current stay: filed on the
     * stay's own encounter, or (no admission link) on an INPATIENT encounter
     * or on no encounter at all, written since the admission. An order of an
     * outpatient visit is the visit's, never a nursing dose.
     */
    public static boolean orderOfStay(Prescription rx, Admission stay) {
        if (stay == null) return false;
        Encounter encounter = rx.getEncounter();
        if (encounter != null && encounter.getAdmissionId() != null) {
            return encounter.getAdmissionId().equals(stay.getId());
        }
        LocalDateTime written = rx.getCreatedAt();
        LocalDateTime from = stay.getAdmissionDateTime();
        if (encounter != null && encounter.getEncounterType() == EncounterType.EMERGENCY) {
            // The department's orders carry on to the ward: the emergency
            // visit ends with the admission (QA D712), its treatment does not.
            // Only the visit still open at admission carries over: a visit
            // checked out before it (the patient went home with a course and
            // came back later) keeps its take-home orders off the ward MAR.
            LocalDateTime checkout = encounter.getCheckoutTimestamp();
            boolean openAtAdmission = checkout == null || from == null || !checkout.isBefore(from);
            return openAtAdmission && written != null
                && (from == null || !written.isBefore(from.minus(ED_TO_WARD_CARRY_OVER)));
        }
        if (encounter != null && encounter.getEncounterType() != EncounterType.INPATIENT) {
            return false;
        }
        // An order with no write time (never, for an order written here) is
        // kept: it names no outpatient visit, and dropping a ward dose is not
        // the safe direction.
        return written == null || from == null || !written.isBefore(from);
    }

    private static boolean sameHospital(Prescription rx, Admission stay) {
        if (rx.getHospital() == null || stay.getHospital() == null) return true;
        return Objects.equals(rx.getHospital().getId(), stay.getHospital().getId());
    }
}
