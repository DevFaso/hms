package com.example.hms.service;

import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;

import java.util.List;
import java.util.UUID;

public interface PatientMedicationService {

    List<PatientMedicationResponseDTO> getMedicationsForPatient(UUID patientId, UUID hospitalId, int limit);
}
