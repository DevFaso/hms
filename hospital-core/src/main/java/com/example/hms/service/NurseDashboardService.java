package com.example.hms.service;

import com.example.hms.payload.dto.PatientResponseDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface NurseDashboardService {
    /**
     * QA D1314: the same patients in the same order as
     * {@link #getPatientsForNurse} with no date, carrying only the id and the
     * name: what the ward's lists read. No vitals, no allergies, no flags.
     */
    List<PatientResponseDTO> getPatientNamesForNurse(UUID nurseUserId, UUID hospitalId);

    List<PatientResponseDTO> getPatientsForNurse(UUID nurseUserId, UUID hospitalId, LocalDate inhouseDate);
}
