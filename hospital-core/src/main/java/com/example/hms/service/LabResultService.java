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

    /**
     * Entry point for the HL7 inbound adapter.
     *
     * <p>Identical to {@link #createLabResult} except in where the tenant
     * boundary comes from. An interface account posts an ORU with no {@code
     * X-Hospital-Id} and possibly no assignment of its own, so there is no
     * active hospital to compare the order against; the sending pair in the
     * message header (MSH-3 / MSH-4) is resolved against the MLLP allowlist
     * instead, and {@code senderHospitalId} is the hospital that allowlist
     * entry points at. The order must be one that hospital handles.
     *
     * <p>{@code senderHospitalId} is mandatory: it is the whole tenant
     * boundary on this path. A null one is refused exactly as an order at
     * another hospital is, so a caller cannot reach an order by omitting the
     * identification the allowlist would have supplied. A caller that DOES
     * have a resolvable hospital scope is still pinned to it as well — being
     * named by an allowlist entry does not let a lab user of one hospital
     * write into another.
     *
     * @param senderHospitalId the hospital the allowlisted sending pair
     *                         resolves to; never null in a call that should
     *                         succeed
     */
    LabResultResponseDTO createIngestedLabResult(LabResultRequestDTO requestDTO,
                                                 UUID senderHospitalId,
                                                 Locale locale);

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

    /**
     * The release worklist of the caller's hospital: every result nobody
     * has released yet, hand-entered or analyzer-ingested alike.
     */
    Page<LabResultResponseDTO> getPendingRelease(Pageable pageable, Locale locale);

    LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale);

    // Enhanced trending and comparison methods (Story #5)
    LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale);

    List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale);

    List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale);

    List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale);
}
