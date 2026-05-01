package com.example.hms.service;

import com.example.hms.payload.dto.BreakGlassDeclareRequestDTO;
import com.example.hms.payload.dto.BreakGlassRevokeRequestDTO;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages "break-the-glass" emergency-access sessions — short-lived overrides
 * that let a privileged clinician read a patient's chart without a pre-existing
 * {@link com.example.hms.model.PatientConsent}.
 *
 * <p>Every authorisation served under a live session emits a
 * {@code BREAK_GLASS_ACCESS} audit event. Authorisation flows that may consume
 * a session must call {@link #consumeIfLive(UUID, UUID, String)} so the count
 * + audit trail stays accurate.
 */
public interface BreakGlassService {

    /**
     * Declares a new emergency-access session for the calling user.
     *
     * <p>The caller must hold one of the privileged clinical or administrative
     * roles (DOCTOR / NURSE / MIDWIFE / HOSPITAL_ADMIN / SUPER_ADMIN); enforcement
     * lives in the controller via {@code @PreAuthorize}, but the service also
     * verifies the user/hospital/patient exist.
     *
     * @return the newly created session as a response DTO
     */
    BreakGlassSessionResponseDTO declare(BreakGlassDeclareRequestDTO request);

    /**
     * Revokes an active session. Allowed for: the declaring user, any
     * HOSPITAL_ADMIN of the session's hospital, or SUPER_ADMIN. No-op (idempotent)
     * if the session is already revoked or expired.
     */
    BreakGlassSessionResponseDTO revoke(UUID sessionId, BreakGlassRevokeRequestDTO request);

    /** Live sessions for a given patient (powers the patient-detail banner). */
    List<BreakGlassSessionResponseDTO> listLiveForPatient(UUID patientId);

    /** Hospital-scoped audit page (compliance review screen). */
    Page<BreakGlassSessionResponseDTO> listForHospital(UUID hospitalId, Pageable pageable);

    /**
     * Returns the current user's live session for the given patient if one
     * exists. Used by authorisation flows to determine whether to fall back
     * to break-glass after a normal consent check fails.
     *
     * <p>This call <b>does not</b> emit audit events. Call
     * {@link #consumeIfLive(UUID, UUID, String)} on the read path instead.
     */
    Optional<BreakGlassSessionResponseDTO> findLiveForCurrentUserAndPatient(UUID patientId);

    /**
     * Looks up a live session for the supplied user + patient (or the current
     * security context when {@code sessionUserId} is null), bumps its audit
     * counter via an atomic {@code UPDATE … SET audit_count = audit_count + 1},
     * and emits a {@code BREAK_GLASS_ACCESS} audit event with the supplied
     * {@code purpose}. Returns the session DTO if one was consumed, empty
     * otherwise (caller should fall through to its existing access check).
     *
     * <p>The atomic UPDATE keeps the counter race-free under concurrent reads;
     * the in-memory entity used to render the response DTO is bumped purely so
     * the caller observes the new value without a re-fetch.
     */
    Optional<BreakGlassSessionResponseDTO> consumeIfLive(UUID patientId, UUID sessionUserId, String purpose);
}
