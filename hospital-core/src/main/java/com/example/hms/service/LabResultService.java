package com.example.hms.service;

import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface LabResultService {

    LabResultResponseDTO createLabResult(LabResultRequestDTO requestDTO, Locale locale);

    LabResultResponseDTO getLabResultById(UUID id, Locale locale);

    List<LabResultResponseDTO> getAllLabResults(Locale locale);

    List<LabResultResponseDTO> getPendingReviewResults(UUID providerId, Locale locale);

    LabResultResponseDTO updateLabResult(UUID id, LabResultRequestDTO requestDTO, Locale locale);

    void deleteLabResult(UUID id, Locale locale);

    List<LabResultResponseDTO> getLabResultsByLabOrderId(UUID labOrderId, Locale locale);

    List<LabResultResponseDTO> getLabResultsByPatientId(UUID patientId, Locale locale);

    void acknowledgeLabResult(UUID id, Locale locale);

    LabResultResponseDTO releaseLabResult(UUID id, Locale locale);

    LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale);

    // Enhanced trending and comparison methods (Story #5)
    LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale);

    List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale);

    List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale);

    List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale);
}
