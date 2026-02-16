package com.example.hms.service;

import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientPrimaryCareService {

    PatientPrimaryCareResponseDTO assignPrimaryCare(UUID patientId, PatientPrimaryCareRequestDTO request);

    PatientPrimaryCareResponseDTO updatePrimaryCare(UUID pcpId, PatientPrimaryCareRequestDTO request);

    PatientPrimaryCareResponseDTO endPrimaryCare(UUID pcpId, java.time.LocalDate endDate);

    Optional<PatientPrimaryCareResponseDTO> getCurrentPrimaryCare(UUID patientId);

    List<PatientPrimaryCareResponseDTO> getPrimaryCareHistory(UUID patientId);

    void deletePrimaryCare(UUID pcpId);
}
