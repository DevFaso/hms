package com.example.hms.service;

import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Dispatches a prescription summary by SMS to a community / partner pharmacy.
 * Pairs with the patient flow where pickup happens at a non-hospital pharmacy
 * (West-Africa context). Records a {@code PrescriptionTransmission} for audit.
 */
public interface PrescriptionSmsDispatchService {

    PrescriptionSmsDispatchResponseDTO dispatch(Authentication auth,
                                                UUID prescriptionId,
                                                PrescriptionSmsDispatchRequestDTO request);
}
