package com.example.hms.service;

import com.example.hms.payload.dto.GuarantorRequestDTO;
import com.example.hms.payload.dto.GuarantorResponseDTO;

import java.util.List;
import java.util.UUID;

/** Guarantor accounts (P3 #21): deactivate-never-delete. */
public interface PatientGuarantorService {

    GuarantorResponseDTO add(UUID patientId, UUID hospitalId, GuarantorRequestDTO request);

    GuarantorResponseDTO update(UUID patientId, UUID guarantorId, UUID hospitalId,
                                GuarantorRequestDTO request);

    List<GuarantorResponseDTO> list(UUID patientId, UUID hospitalId);

    /** QA D1247 (round 25): a deactivation states why; the reason, time and actor are kept. */
    GuarantorResponseDTO deactivate(UUID patientId, UUID guarantorId, UUID hospitalId, String reason, UUID actorUserId);

    GuarantorResponseDTO reactivate(UUID patientId, UUID guarantorId, UUID hospitalId);
}
