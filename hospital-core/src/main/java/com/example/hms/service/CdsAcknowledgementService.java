package com.example.hms.service;

import com.example.hms.payload.dto.cds.CdsAcknowledgementRequestDTO;
import com.example.hms.payload.dto.cds.CdsAcknowledgementResponseDTO;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface CdsAcknowledgementService {

    CdsAcknowledgementResponseDTO acknowledge(Authentication auth, CdsAcknowledgementRequestDTO request);

    /**
     * Active (non-expired) acknowledgements for a patient. Used to suppress
     * already-handled cards. The caller's {@link Authentication} is required
     * so the service can enforce that the user has access to the patient
     * before returning rows.
     */
    List<CdsAcknowledgementResponseDTO> activeForPatient(Authentication auth, UUID patientId);
}
