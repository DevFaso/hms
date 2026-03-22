package com.example.hms.service;

import com.example.hms.payload.dto.lab.LabResultTrendDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import java.util.List;
import java.util.UUID;

public interface PatientLabResultService {

    List<PatientLabResultResponseDTO> getLabResultsForPatient(UUID patientId, UUID hospitalId, int limit);

    /**
     * Groups all released lab results for the patient by test type and returns up to 12
     * data points per test, most-recent-first, for trend charting.
     */
    List<LabResultTrendDTO> getLabResultTrends(UUID patientId);
}
