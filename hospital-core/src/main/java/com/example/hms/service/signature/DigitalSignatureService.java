package com.example.hms.service.signature;

import com.example.hms.enums.SignatureType;
import com.example.hms.payload.dto.signature.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for digital signature operations.
 * Story #17: Generic Report Signing API
 */
public interface DigitalSignatureService {

    /**
     * Sign a clinical report with electronic signature
     * 
     * @param request signature request containing report details and signature value
     * @return created signature response
     */
    SignatureResponseDTO signReport(SignatureRequestDTO request);

    /**
     * Verify a signature against stored hash
     * 
     * @param request verification request containing signature value to verify
     * @return verification response with validity status
     */
    SignatureVerificationResponseDTO verifySignature(SignatureVerificationRequestDTO request);

    /**
     * Revoke an existing signature
     * 
     * @param signatureId ID of signature to revoke
     * @param request revocation request with reason
     * @return updated signature response
     */
    SignatureResponseDTO revokeSignature(UUID signatureId, SignatureRevocationRequestDTO request);

    /**
     * Get all signatures for a specific report
     * 
     * @param reportType type of report
     * @param reportId ID of report
     * @return list of signatures for the report
     */
    List<SignatureResponseDTO> getSignaturesByReportId(SignatureType reportType, UUID reportId);

    /**
     * Get all signatures created by a specific provider
     * 
     * @param providerId staff ID of provider
     * @return list of signatures by provider
     */
    List<SignatureResponseDTO> getSignaturesByProvider(UUID providerId);

    /**
     * Get a specific signature by ID
     * 
     * @param signatureId ID of signature
     * @return signature response
     */
    SignatureResponseDTO getSignatureById(UUID signatureId);

    /**
     * Get audit trail for a specific signature
     * 
     * @param signatureId ID of signature
     * @return list of audit entries
     */
    List<SignatureAuditEntryDTO> getSignatureAuditTrail(UUID signatureId);

    /**
     * Check if a report has been signed
     * 
     * @param reportType type of report
     * @param reportId ID of report
     * @return true if report has at least one valid signature
     */
    boolean isReportSigned(SignatureType reportType, UUID reportId);

    /**
     * Get signatures by hospital within a date range
     * 
     * @param hospitalId hospital ID
     * @param startDate start of date range
     * @param endDate end of date range
     * @return list of signatures within date range
     */
    List<SignatureResponseDTO> getSignaturesByHospitalAndDateRange(
        UUID hospitalId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Process expired signatures (batch job utility)
     * Updates status of signatures past their expiration date
     * 
     * @return number of signatures updated
     */
    int processExpiredSignatures();
}
