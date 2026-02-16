package com.example.hms.service;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface PrescriptionService {

    PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request, Locale locale);

    PrescriptionResponseDTO getPrescriptionById(UUID id, Locale locale);

    Page<PrescriptionResponseDTO> list(UUID patientId, UUID staffId, UUID encounterId, Pageable pageable, Locale locale);

    PrescriptionResponseDTO updatePrescription(UUID id, PrescriptionRequestDTO request, Locale locale);

    void deletePrescription(UUID id, Locale locale);

    // legacy convenience (optional)
    List<PrescriptionResponseDTO> getPrescriptionsByPatientId(UUID patientId, Locale locale);
    List<PrescriptionResponseDTO> getPrescriptionsByStaffId(UUID staffId, Locale locale);
    List<PrescriptionResponseDTO> getPrescriptionsByEncounterId(UUID encounterId, Locale locale);
}
