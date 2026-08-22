package com.example.hms.service;

import com.example.hms.enums.TreatmentConsentSource;
import com.example.hms.payload.dto.TreatmentConsentRequestDTO;
import com.example.hms.payload.dto.TreatmentConsentResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Consent-to-treat records (P3 #21). A record, not a gate: nothing here
 * blocks a check-in or an encounter — refusing treatment for a missing
 * signature is a clinical-workflow decision deliberately left open.
 */
public interface TreatmentConsentService {

    TreatmentConsentResponseDTO record(UUID patientId, UUID hospitalId, UUID actorUserId,
                                       TreatmentConsentSource source, TreatmentConsentRequestDTO request);

    List<TreatmentConsentResponseDTO> getForPatient(UUID patientId, UUID hospitalId);

    TreatmentConsentResponseDTO revoke(UUID consentId, UUID hospitalId, UUID actorUserId, String reason);

    /** True when the patient has an ACTIVE consent at the hospital — the check-in badge. */
    boolean hasActiveConsent(UUID patientId, UUID hospitalId);
}
