package com.example.hms.service;

import com.example.hms.config.SecurityConstants;

import java.util.Set;

/**
 * The role sets for the reads that take a patient id, or the id of a row a
 * patient owns, and admit {@code ROLE_PATIENT} — one per endpoint family, each
 * handed to {@link PatientSubjectReadGuard}.
 *
 * <p>Every one of these reads admitted {@code ROLE_PATIENT} and then served
 * whatever id arrived: a patient read another patient's consultations,
 * imaging orders and reports, procedure orders, ultrasound orders and reports,
 * and appointments. The guard closes that; these sets decide who it applies to.
 *
 * <p><b>The invariant, for every set below and any set added later: exactly
 * the endpoint's {@code @PreAuthorize} minus {@code ROLE_PATIENT}.</b> Nothing
 * added, nothing inferred — the rule {@link EncounterReaderRoles} and
 * {@link PrescriptionReaderRoles} already hold, and for the same reason. These
 * sets do not GRANT access, the annotation did that; they REMOVE subject
 * status. A role the annotation does not admit reaches the handler only
 * through {@code ROLE_PATIENT}, so naming it here would reclassify that
 * patient as staff and skip the ownership test: that is why
 * {@code ROLE_PHYSICIAN} and {@code ROLE_SURGEON} are absent even though
 * {@code RoleExpansion} maps them to {@code ROLE_DOCTOR} on the password path.
 * A role the annotation admits but the set omits is the opposite defect: that
 * clinician would be refused every record that is not their own.
 * {@code PatientSubjectReaderRolesMirrorTest} reads each compiled annotation
 * and fails on drift in either direction.
 *
 * <p>{@code ROLE_SUPER_ADMIN} is in every set because every one of these
 * annotations admits it, and it must win over the {@code ROLE_PATIENT} that
 * {@code RoleExpansion.SUPER_ADMIN_INHERITS} grants every super-admin on the
 * password path.
 *
 * <p>Where two endpoints carry the same annotation they share a set, and the
 * mirror test pins each of them to it separately, so the day one annotation
 * changes, the shared set stops matching it and the test says so.
 */
public final class PatientSubjectReaderRoles {

    /**
     * {@code GET /consultations/patient/{patientId}} —
     * {@code hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE','PATIENT')}.
     */
    public static final Set<String> CONSULTATIONS_BY_PATIENT = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_MIDWIFE);

    /**
     * {@code GET /imaging/orders/patient/{patientId}} —
     * {@code hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')}.
     */
    public static final Set<String> IMAGING_ORDERS_BY_PATIENT = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_RADIOLOGIST);

    /**
     * {@code GET /imaging/results/{reportId}} and
     * {@code GET /imaging/results/order/{orderId}} — both
     * {@code hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')}.
     */
    public static final Set<String> IMAGING_REPORT_READS = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_RADIOLOGIST);

    /**
     * {@code GET /procedure-orders/{orderId}} and
     * {@code GET /procedure-orders/patient/{patientId}} — both
     * {@code hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','PATIENT')}.
     */
    public static final Set<String> PROCEDURE_ORDER_READS = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE);

    /**
     * {@code GET /ultrasound/orders/{orderId}},
     * {@code GET /ultrasound/orders/patient/{patientId}},
     * {@code GET /ultrasound/reports/{reportId}} and
     * {@code GET /ultrasound/reports/order/{orderId}} — all four
     * {@code hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE','NURSE','PATIENT')}.
     */
    public static final Set<String> ULTRASOUND_READS = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_NURSE);

    /**
     * {@code GET /appointments/{id}}, {@code GET /appointments/patients/{patientId}}
     * and {@code GET /appointments/patients/username/{patientUsername}} — all
     * three carry {@code AppointmentController.APPOINTMENT_READ_ROLES}, which
     * concatenates {@code SecurityConstants.CONSULTING_CLINICIANS_ROLES}.
     *
     * <p>{@code ROLE_HOSPITAL_ADMIN}, {@code ROLE_STAFF} and
     * {@code ROLE_RECEPTIONIST} are here because the annotation admits them:
     * the front desk reads the calendar. The three consulting clinicians are
     * here for the same reason (role audit D7).
     */
    public static final Set<String> APPOINTMENT_READS = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_HOSPITAL_ADMIN,
        SecurityConstants.ROLE_STAFF,
        SecurityConstants.ROLE_RECEPTIONIST,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_RADIOLOGIST,
        SecurityConstants.ROLE_ANESTHESIOLOGIST,
        SecurityConstants.ROLE_PHYSIOTHERAPIST);

    private PatientSubjectReaderRoles() {
    }
}
