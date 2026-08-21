package com.example.hms.service;

import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface LabResultService {

    LabResultResponseDTO createLabResult(LabResultRequestDTO requestDTO, Locale locale);

    LabResultResponseDTO getLabResultById(UUID id, Locale locale);

    List<LabResultResponseDTO> getAllLabResults(Locale locale);

    Page<LabResultResponseDTO> getLabResultsPage(Pageable pageable, Locale locale);

    LabResultResponseDTO updateLabResult(UUID id, LabResultRequestDTO requestDTO, Locale locale);

    void deleteLabResult(UUID id, Locale locale);

    List<LabResultResponseDTO> getLabResultsByLabOrderId(UUID labOrderId, Locale locale);

    List<LabResultResponseDTO> getLabResultsByPatientId(UUID patientId, Locale locale);

    void acknowledgeLabResult(UUID id, Locale locale);

    /**
     * Record the receiving clinician's read-back of a critical value (P0 #5).
     *
     * <p>A matching read-back acknowledges the result and stops escalation. A
     * mismatch is rejected and recorded — that is the error the read-back exists
     * to catch.
     */
    com.example.hms.payload.dto.LabResultResponseDTO recordCriticalReadBack(
        UUID id,
        com.example.hms.payload.dto.CriticalValueReadBackRequestDTO request,
        Locale locale);

    LabResultResponseDTO releaseLabResult(UUID id, Locale locale);

    LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale);

    // Enhanced trending and comparison methods (Story #5)
    LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale);

    List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale);

    List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale);

    List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale);
}
