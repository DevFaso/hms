package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.DisplayDates;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * QA D1488 (round 36): a recorded death takes effect. {@code Patient.deceasedAt} was stamped and nothing
 * read it, so a dead patient was admitted, prescribed for, put in labour and booked.
 *
 * <p>The one check every write of NEW care goes through, called AFTER the write's own tenant and
 * registration check: a caller who cannot see the patient gets the same 404 as ever, and never learns from
 * this refusal that the patient has died. Static and keyed on the loaded patient, as
 * {@link PatientRegistrationGuard#refuseDeactivatedRegistration} is.
 *
 * <p><b>{@link #careBlocked} is the one eligibility rule</b>, read by every refusal here, by the chart and by the
 * desk's list. A doctor's open dispute of the death at the acting hospital lifts it for that hospital alone
 * ({@code DeathDisputeService}), so an admission can never succeed with medication or blood still blocked downstream:
 * no entry point has an exemption of its own, and normal clinical and permission checks all go on running.
 *
 * <p>What stays open after a death (reads, discharge and close-out, billing, results of samples collected
 * before the death, late documentation with its real time, the death record, transfusion reactions,
 * certificates) and every write entry point's classification are listed in
 * {@code DeceasedPatientCareGuardTest}, which fails until a new entry point is classified.
 */
public final class DeceasedPatientGuard {

    public static final String NO_NEW_CARE_KEY = "patient.deceased.noNewCare";
    public static final String AFTER_DEATH_KEY = "patient.deceased.careAfterDeath";

    private static final String FIELD_PATIENT = "patientId";

    /** (patient, hospital) has an open dispute. Set at startup by {@link DeathCareEligibility}; none means blocked. */
    private static volatile BiPredicate<UUID, UUID> openDispute;

    private DeceasedPatientGuard() {
    }

    /** Package level for {@link DeathCareEligibility}; public tests reach it through that bean. */
    static void useDisputes(BiPredicate<UUID, UUID> disputes) {
        openDispute = disputes;
    }

    /** THE rule: a recorded death blocks new care, unless a doctor has disputed it at the acting hospital. */
    public static boolean careBlocked(Patient patient) {
        return patient != null && patient.isDeceased() && !deathDisputedHere(patient);
    }

    /** A recorded death under an open dispute at the acting hospital. Another hospital stays blocked. */
    public static boolean deathDisputedHere(Patient patient) {
        if (patient == null || !patient.isDeceased() || openDispute == null) {
            return false;
        }
        UUID hospital = ActingScopeResolver.pinnedHospitalIdOrNull();
        return hospital != null && openDispute.test(patient.getId(), hospital);
    }

    /** Refuses a new order, visit, booking or episode, on the patient. No patient (an anonymous sale) is not refused. */
    public static void refuseNewCare(Patient patient) {
        if (careBlocked(patient)) {
            throw new FieldBusinessException(FIELD_PATIENT, NO_NEW_CARE_KEY, DisplayDates.dateTime(patient.getDeceasedAt()));
        }
    }

    /**
     * Refuses an observation timed after the death, on its time field. One timed before it is late
     * documentation of care given, and stays open. No time means now.
     */
    public static void refuseCareAfterDeath(Patient patient, LocalDateTime at, String field) {
        if (!careBlocked(patient)) {
            return;
        }
        LocalDateTime when = at != null ? at : LocalDateTime.now(ZoneId.systemDefault());
        if (when.isAfter(patient.getDeceasedAt())) {
            throw new FieldBusinessException(field, AFTER_DEATH_KEY, DisplayDates.dateTime(patient.getDeceasedAt()));
        }
    }

    /** The same for an observation dated by the day alone: only a later day is known to be after the death. */
    public static void refuseCareAfterDeathDay(Patient patient, LocalDate day, String field) {
        if (!careBlocked(patient)) {
            return;
        }
        LocalDate on = day != null ? day : LocalDate.now(ZoneId.systemDefault());
        if (on.isAfter(patient.getDeceasedAt().toLocalDate())) {
            throw new FieldBusinessException(field, AFTER_DEATH_KEY, DisplayDates.dateTime(patient.getDeceasedAt()));
        }
    }
}
