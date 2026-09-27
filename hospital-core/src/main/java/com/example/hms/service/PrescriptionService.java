package com.example.hms.service;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface PrescriptionService {

    PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request, Locale locale);

    /**
     * The guarded read: hospital scope, and a patient principal may only read
     * their own prescription. This is the one to call for a READ.
     *
     * <p>A patient-only caller is bounded by ownership, not by a hospital: no
     * scope is resolved, and they read their own prescription wherever it
     * was written. For everyone else a {@code null} hospital scope reads
     * across tenants only for a verified super-admin
     * ({@code isSuperAdminFromJwtClaim()}); any other caller without a
     * hospital is refused before the lookup. A staff member who holds
     * {@code ROLE_PATIENT} and owns a prescription at another hospital reads
     * it too, as its patient: the patient copy, without the clarification
     * exchange. Every other refusal answers exactly as a missing id does.
     */
    PrescriptionResponseDTO getPrescriptionById(UUID id, Locale locale);

    /**
     * The same read WITHOUT the patient-ownership guard, for the read-back a
     * write endpoint returns after it has already authorised and committed the
     * write.
     *
     * <p>Exactly two callers, and
     * {@code PrescriptionAfterWriteCallerGuardTest} fails if that changes:
     * {@code pharmacist-verify} and {@code request-clarification}. Both admit
     * {@code ROLE_SUPER_ADMIN}, which the by-id read does not, so putting them
     * through the guard would let an actor who also happens to be a patient
     * commit the write and then be told 404 by the response to it, with a
     * retry refused as already done.
     *
     * <p>{@code resolve-clarification} is deliberately NOT a caller: it is
     * {@code ROLE_DOCTOR}-only, so the guard is a no-op for every principal
     * that can reach it and routing it here would widen the unguarded surface
     * for nothing. Do not add a caller without that kind of reason, and never
     * from a read path — hospital scope still applies here, ownership does
     * not.
     */
    PrescriptionResponseDTO getPrescriptionAfterWrite(UUID id, Locale locale);

    /**
     * Sign a prescription (P2 #16).
     *
     * <p>SIGNED used to be a client-supplied status with nothing behind it. This
     * is the only path that can set it: it verifies the signer is the
     * prescription's own prescriber, applies the controlled-substance gates, and
     * records who signed, when, and a SHA-256 digest of what they signed.
     */
    PrescriptionResponseDTO signPrescription(UUID id, Locale locale);

    /**
     * Co-sign a prescription that declared it needs one (P2 #15).
     *
     * <p>The {@code cosignedBy}/{@code cosignedAt} columns existed since the
     * pharmacy module with no path ever writing them, so a prescription flagged
     * {@code requiresCosign} was permanently unsignable once the gates went in.
     * The co-signer must be a prescriber OTHER than the prescription's own —
     * a second pair of eyes is the entire point.
     */
    PrescriptionResponseDTO cosignPrescription(UUID id, Locale locale);

    /**
     * The prescription list, hospital-scoped.
     *
     * @param statuses gap G12 — restrict the page to these {@code PrescriptionStatus}
     *                 values. {@code null} or empty means every status, which is what
     *                 the endpoint did before the parameter existed.
     */
    Page<PrescriptionResponseDTO> list(UUID patientId, UUID staffId, UUID encounterId,
                                       List<PrescriptionStatus> statuses,
                                       Pageable pageable, Locale locale);

    PrescriptionResponseDTO updatePrescription(UUID id, PrescriptionRequestDTO request, Locale locale);

    void deletePrescription(UUID id, Locale locale);

    // legacy convenience (optional)
    List<PrescriptionResponseDTO> getPrescriptionsByPatientId(UUID patientId, Locale locale);
    List<PrescriptionResponseDTO> getPrescriptionsByStaffId(UUID staffId, Locale locale);
    List<PrescriptionResponseDTO> getPrescriptionsByEncounterId(UUID encounterId, Locale locale);
}
