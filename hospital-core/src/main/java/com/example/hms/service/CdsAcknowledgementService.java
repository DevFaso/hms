package com.example.hms.service;

import com.example.hms.payload.dto.cds.CdsAcknowledgementRequestDTO;
import com.example.hms.payload.dto.cds.CdsAcknowledgementResponseDTO;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface CdsAcknowledgementService {

    CdsAcknowledgementResponseDTO record(Authentication auth, CdsAcknowledgementRequestDTO request);

    /** Active (non-expired) acknowledgements for a patient. Used to suppress already-handled cards. */
    List<CdsAcknowledgementResponseDTO> activeForPatient(UUID patientId);
}
