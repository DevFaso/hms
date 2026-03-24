package com.example.hms.service;

import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import java.util.List;
import java.util.UUID;

public interface PatientLabResultService {

    List<PatientLabResultResponseDTO> getLabResultsForPatient(UUID patientId, UUID hospitalId, int limit);
}
