package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.BusinessException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * QA D788: how an outpatient visit may be closed, written once for every path
 * that writes an encounter's status (the check-out, {@code PUT /encounters/{id}},
 * the reception flow board's {@code PATCH .../status}).
 *
 * <ul>
 *   <li>A visit is COMPLETED only after the clinical completion step: the
 *       clinician completed the examination (AWAITING_RESULTS) or marked the
 *       patient ready for discharge (READY_FOR_DISCHARGE), or the clinician
 *       closes their own consultation (IN_PROGRESS) with the check-out.
 *       Reception checked out an ARRIVED walk-in nobody had seen, and it was
 *       counted as a consultation with an After-Visit Summary.</li>
 *   <li>A visit no clinician has seen (SCHEDULED, ARRIVED, TRIAGE,
 *       WAITING_FOR_PHYSICIAN) is closed as "left without being seen" or
 *       cancelled, with a reason ({@code POST /encounters/{id}/close-unseen}),
 *       and becomes LEFT_WITHOUT_BEING_SEEN or CANCELLED: never a consultation.</li>
 *   <li>The clinical statuses (IN_PROGRESS, AWAITING_RESULTS,
 *       READY_FOR_DISCHARGE) are reached through the clinician's steps, never
 *       set by the desk on the flow board.</li>
 *   <li>A closed visit ({@link EncounterStatus#CLOSED}) is not reopened by a status write.</li>
 * </ul>
 */
public final class EncounterClosureRules {

    /** No clinician has seen the patient yet. */
    public static final Set<EncounterStatus> NEVER_SEEN = Collections.unmodifiableSet(EnumSet.of(
        EncounterStatus.SCHEDULED, EncounterStatus.ARRIVED, EncounterStatus.TRIAGE,
        EncounterStatus.WAITING_FOR_PHYSICIAN));

    /** The examination is complete: the visit may be checked out. */
    public static final Set<EncounterStatus> CLINICALLY_COMPLETE = Collections.unmodifiableSet(EnumSet.of(
        EncounterStatus.AWAITING_RESULTS, EncounterStatus.READY_FOR_DISCHARGE));

    /** Reached through the clinician's own steps (start, complete examination). */
    public static final Set<EncounterStatus> CLINICIAN_STATUSES = Collections.unmodifiableSet(EnumSet.of(
        EncounterStatus.IN_PROGRESS, EncounterStatus.AWAITING_RESULTS, EncounterStatus.READY_FOR_DISCHARGE));

    /** The roles that conduct a consultation (the complete-examination endpoint's clinicians). */
    private static final Set<String> CLINICIAN_AUTHORITIES = Set.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE");

    private EncounterClosureRules() {
    }

    /** Whether the signed-in caller conducts consultations (doctor, nurse, midwife). */
    public static boolean callerIsClinician() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(CLINICIAN_AUTHORITIES::contains);
    }

    public static boolean isNeverSeen(EncounterStatus status) {
        return status == null || NEVER_SEEN.contains(status);
    }

    /**
     * The check-out's own rule. COMPLETED and CANCELLED are refused by the
     * caller first ({@code encounter.checkout.alreadyClosed}).
     *
     * @param clinicianCaller a doctor, nurse or midwife: their check-out of a
     *                        visit IN_PROGRESS completes their own consultation
     *                        (the tracker's "Check out" on a patient being
     *                        seen); the desk needs the examination completed first
     */
    public static void requireCheckoutable(EncounterStatus current, boolean clinicianCaller) {
        if (CLINICALLY_COMPLETE.contains(current)
                || (current == EncounterStatus.IN_PROGRESS && clinicianCaller)) {
            return;
        }
        if (isNeverSeen(current)) {
            throw new BusinessException("encounter.checkout.notSeen", EncounterStatus.labelOf(current));
        }
        throw new BusinessException("encounter.checkout.examinationNotCompleted", EncounterStatus.labelOf(current));
    }

    /**
     * A status written directly ({@code PUT /encounters/{id}}, the flow board),
     * outside the workflow endpoints.
     *
     * @throws BusinessException when the write would close a visit without its
     *                           clinical completion, or cancel one without a reason
     */
    public static void requireDirectTransition(EncounterStatus from, EncounterStatus to, boolean clinicianCaller) {
        if (to == null || to == from) {
            return;
        }
        // A closed visit is not reopened by a status write: its check-out (AVS,
        // appointment, recall) or its close-unseen reason would stay behind it.
        if (from != null && from.isClosed()) {
            throw new BusinessException("encounter.status.closed", from.localizedLabel());
        }
        // Cancelled and left-without-being-seen both carry a reason: only the
        // close-unseen endpoint writes them.
        if (EncounterStatus.ENDED_UNSEEN.contains(to)) {
            throw new BusinessException("encounter.status.cancelThroughCloseUnseen");
        }
        if (to == EncounterStatus.COMPLETED && !CLINICALLY_COMPLETE.contains(from)
                && !(from == EncounterStatus.IN_PROGRESS && clinicianCaller)) {
            throw new BusinessException("encounter.status.notCompletable", EncounterStatus.labelOf(from));
        }
        if ((to == EncounterStatus.AWAITING_RESULTS || to == EncounterStatus.READY_FOR_DISCHARGE)
                && isNeverSeen(from)) {
            throw new BusinessException("encounter.status.notSeen", EncounterStatus.labelOf(from), to.localizedLabel());
        }
        // ARRIVED, TRIAGE, WAITING_FOR_PHYSICIAN, IN_PROGRESS, SCHEDULED: no closing rule.
    }

    /** The desk (reception, administrators) never sets a clinical status by hand. */
    public static void requireDeskTransition(EncounterStatus from, EncounterStatus to) {
        if (to != null && to != from && CLINICIAN_STATUSES.contains(to)) {
            throw new BusinessException("encounter.status.clinicianOnly", to.localizedLabel());
        }
        requireDirectTransition(from, to, false);
    }
}
