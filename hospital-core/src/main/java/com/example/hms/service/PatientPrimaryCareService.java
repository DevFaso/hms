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

    /**
     * Every primary-care link of the patient's OWN record, current and past, newest first, at
     * every hospital where the patient holds an active registration, whatever hospital the
     * request names (QA D1850). The caller resolves {@code patientId} from the signed-in
     * patient; never call it with an id taken from the request.
     */
    List<PatientPrimaryCareResponseDTO> getOwnPrimaryCareLinks(UUID patientId);

    void deletePrimaryCare(UUID pcpId);
}
