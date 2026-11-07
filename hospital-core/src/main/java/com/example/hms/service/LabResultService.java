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

    /**
     * QA D211: the staff list. {@code includeUnreleased} is true only for
     * laboratory staff; {@code patientId} (nullable) narrows to one patient.
     */
    Page<LabResultResponseDTO> getLabResultsPage(Pageable pageable, Locale locale,
                                                 UUID patientId, boolean includeUnreleased);

    /** QA D781: as above, also narrowed to one lab order when {@code labOrderId} is set. */
    Page<LabResultResponseDTO> getLabResultsPage(Pageable pageable, Locale locale,
                                                 UUID patientId, UUID labOrderId, boolean includeUnreleased);

    /** QA D211: an unreleased result is not found for a non-laboratory reader. */
    LabResultResponseDTO getLabResultById(UUID id, Locale locale, boolean includeUnreleased);

    LabResultResponseDTO updateLabResult(UUID id, LabResultRequestDTO requestDTO, Locale locale);

    void deleteLabResult(UUID id, Locale locale);

    List<LabResultResponseDTO> getLabResultsByLabOrderId(UUID labOrderId, Locale locale);

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

    /**
     * Releases a result. When the caller also entered it (a single-reviewer
     * laboratory, D58) the release must be confirmed explicitly with
     * {@code selfReleaseConfirmed}; it is then audited as a self-release.
     */
    LabResultResponseDTO releaseLabResult(UUID id, boolean selfReleaseConfirmed, Locale locale);

    /** A release that has not confirmed a self-release. */
    LabResultResponseDTO releaseLabResult(UUID id, Locale locale);

    /**
     * QA D938/D178: everything a release tells people (the ordering
     * clinician's in-basket item, the patient's notification), for a result a
     * path OUTSIDE this service released - today the HL7 auto-release. Call it
     * inside the transaction that set {@code released}; the writes are
     * registered for after its commit.
     */
    void announceReleasedResult(com.example.hms.model.LabResult result);

    /**
     * The release worklist of the caller's hospital: every result nobody
     * has released yet, hand-entered or analyzer-ingested alike.
     */
    Page<LabResultResponseDTO> getPendingRelease(Pageable pageable, Locale locale);

    LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale);

    // Enhanced trending and comparison methods (Story #5)
    default LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale) {
        return compareLabResults(currentResultId, locale, true);
    }

    /**
     * QA D211: {@code includeUnreleased} is false for a reader outside the
     * laboratory, who then gets neither an unreleased compared result (404)
     * nor unreleased points in its trend.
     */
    LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale, boolean includeUnreleased);

    default List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale) {
        return compareSequentialResults(patientId, testDefinitionId, locale, true);
    }

    /** QA D211: unreleased results are left out unless {@code includeUnreleased}. */
    List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale,
                                                          boolean includeUnreleased);

    List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale);

    List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale);
}
