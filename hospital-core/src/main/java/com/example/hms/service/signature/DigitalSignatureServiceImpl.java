package com.example.hms.service.signature;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DigitalSignatureMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.payload.dto.signature.SignatureAuditEntryDTO;
import com.example.hms.payload.dto.signature.SignatureRequestDTO;
import com.example.hms.payload.dto.signature.SignatureResponseDTO;
import com.example.hms.payload.dto.signature.SignatureRevocationRequestDTO;
import com.example.hms.payload.dto.signature.SignatureVerificationRequestDTO;
import com.example.hms.payload.dto.signature.SignatureVerificationResponseDTO;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of digital signature service.
 * Story #17: Generic Report Signing API
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DigitalSignatureServiceImpl implements DigitalSignatureService {

    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final String SIGNATURE_NOT_FOUND = "Digital signature not found";
    private static final String STAFF_NOT_FOUND = "Staff member not found";
    private static final String HOSPITAL_NOT_FOUND = "Hospital not found";

    private final DigitalSignatureRepository signatureRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final DigitalSignatureMapper signatureMapper;
    private final AuthService authService;

    @Override
    public SignatureResponseDTO signReport(SignatureRequestDTO request) {
        log.debug("Creating digital signature for report type: {}, ID: {}", 
            request.getReportType(), request.getReportId());

        // Validate staff exists
        Staff staff = staffRepository.findById(request.getSignedByStaffId())
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND));

        // Validate hospital exists
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND));

        // Compute signature hash
        String signatureHash = computeSignatureHash(request.getSignatureValue());

        // Check for duplicate active signatures
        boolean hasSigned = signatureRepository.existsSignedByStaff(
            request.getReportId(), request.getReportType(), staff.getId());
        if (hasSigned) {
            throw new BusinessException(
                "Staff member has already signed this report. Use revoke first to re-sign.");
        }

        // Create signature entity
        DigitalSignature signature = DigitalSignature.builder()
            .reportType(request.getReportType())
            .reportId(request.getReportId())
            .signedBy(staff)
            .hospital(hospital)
            .signatureValue(request.getSignatureValue())
            .signatureDateTime(LocalDateTime.now())
            .status(SignatureStatus.PENDING)
            .signatureHash(signatureHash)
            .ipAddress(request.getIpAddress())
            .deviceInfo(request.getDeviceInfo())
            .signatureNotes(request.getSignatureNotes())
            .expiresAt(request.getExpiresAt())
            .metadata(request.getMetadata())
            .verificationCount(0)
            .build();

        // Mark as signed and add initial audit entry
        signature.markAsSigned();

        // Save signature
        DigitalSignature saved = signatureRepository.save(signature);

        log.info("Digital signature created successfully for report type: {}, ID: {}, Staff: {}", 
            saved.getReportType(), saved.getReportId(), staff.getFullName());

        return signatureMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SignatureVerificationResponseDTO verifySignature(SignatureVerificationRequestDTO request) {
        log.debug("Verifying signature for report type: {}, ID: {}", 
            request.getReportType(), request.getReportId());

        // Compute hash of provided signature value
        String signatureHash = computeSignatureHash(request.getSignatureValue());

        // Find signature(s) for the report
        List<DigitalSignature> signatures;
        if (request.getSignatureId() != null) {
            // Verify specific signature
            DigitalSignature signature = signatureRepository.findById(request.getSignatureId())
                .orElseThrow(() -> new ResourceNotFoundException(SIGNATURE_NOT_FOUND));
            signatures = List.of(signature);
        } else {
            // Find all signatures for report
            signatures = signatureRepository.findActiveSignaturesByReportId(
                request.getReportId(), request.getReportType());
        }

        if (signatures.isEmpty()) {
            return SignatureVerificationResponseDTO.builder()
                .isValid(false)
                .message("No signatures found for this report")
                .invalidReason("No signatures exist")
                .verifiedAt(LocalDateTime.now())
                .build();
        }

        // Check if any signature matches the provided hash
        for (DigitalSignature signature : signatures) {
            if (signature.getSignatureHash().equals(signatureHash)) {
                // Found matching signature
                boolean isValid = signature.isValid();
                
                // Record verification attempt
                UUID currentUserId = authService.getCurrentUserId();
                String currentUserName = getCurrentUserName();
                signature.recordVerification(isValid, currentUserId, currentUserName, 
                    request.getIpAddress(), request.getDeviceInfo());
                signatureRepository.save(signature);

                String message = isValid ? "Signature verified successfully" : "Signature is not valid";
                String invalidReason = isValid ? null : determineInvalidReason(signature);

                return SignatureVerificationResponseDTO.builder()
                    .isValid(isValid)
                    .signatureId(signature.getId())
                    .message(message)
                    .signedByStaffId(signature.getSignedBy().getId())
                    .signedByName(signature.getSignedBy().getFullName())
                    .signatureDateTime(signature.getSignatureDateTime())
                    .status(signature.getStatus().name())
                    .invalidReason(invalidReason)
                    .verificationCount(signature.getVerificationCount())
                    .verifiedAt(LocalDateTime.now())
                    .build();
            }
        }

        // No matching signature found
        return SignatureVerificationResponseDTO.builder()
            .isValid(false)
            .message("Signature verification failed")
            .invalidReason("Signature value does not match any stored signature")
            .verifiedAt(LocalDateTime.now())
            .build();
    }

    @Override
    public SignatureResponseDTO revokeSignature(UUID signatureId, SignatureRevocationRequestDTO request) {
        log.debug("Revoking signature: {}", signatureId);

        DigitalSignature signature = signatureRepository.findById(signatureId)
            .orElseThrow(() -> new ResourceNotFoundException(SIGNATURE_NOT_FOUND));

        if (!signature.canRevoke()) {
            throw new BusinessException("Signature cannot be revoked. Current status: " + signature.getStatus());
        }

        UUID currentUserId = authService.getCurrentUserId();
        String currentUserName = getCurrentUserName();

        signature.revoke(currentUserId, currentUserName, request.getRevocationReason(),
            request.getIpAddress(), request.getDeviceInfo());

        DigitalSignature saved = signatureRepository.save(signature);

        log.info("Signature revoked successfully: {}, Reason: {}", signatureId, request.getRevocationReason());

        return signatureMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureResponseDTO> getSignaturesByReportId(SignatureType reportType, UUID reportId) {
        log.debug("Fetching signatures for report type: {}, ID: {}", reportType, reportId);

        List<DigitalSignature> signatures = signatureRepository
            .findByReportIdAndReportTypeOrderBySignatureDateTimeDesc(reportId, reportType);

        return signatures.stream()
            .map(signatureMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureResponseDTO> getSignaturesByProvider(UUID providerId) {
        log.debug("Fetching signatures for provider: {}", providerId);

        List<DigitalSignature> signatures = signatureRepository
            .findBySignedBy_IdOrderBySignatureDateTimeDesc(providerId);

        return signatures.stream()
            .map(signatureMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SignatureResponseDTO getSignatureById(UUID signatureId) {
        log.debug("Fetching signature: {}", signatureId);

        DigitalSignature signature = signatureRepository.findById(signatureId)
            .orElseThrow(() -> new ResourceNotFoundException(SIGNATURE_NOT_FOUND));

        return signatureMapper.toResponseDTO(signature);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureAuditEntryDTO> getSignatureAuditTrail(UUID signatureId) {
        log.debug("Fetching audit trail for signature: {}", signatureId);

        DigitalSignature signature = signatureRepository.findById(signatureId)
            .orElseThrow(() -> new ResourceNotFoundException(SIGNATURE_NOT_FOUND));

        return signatureMapper.toAuditEntryDTOs(signature.getAuditLog());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReportSigned(SignatureType reportType, UUID reportId) {
        return signatureRepository.hasValidSignature(reportId, reportType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureResponseDTO> getSignaturesByHospitalAndDateRange(
            UUID hospitalId, LocalDateTime startDate, LocalDateTime endDate) {
        log.debug("Fetching signatures for hospital: {} between {} and {}", 
            hospitalId, startDate, endDate);

        List<DigitalSignature> signatures = signatureRepository
            .findBySignatureDateTimeBetween(startDate, endDate);

        // Filter by hospital
        return signatures.stream()
            .filter(s -> s.getHospital() != null && s.getHospital().getId().equals(hospitalId))
            .map(signatureMapper::toResponseDTO)
            .toList();
    }

    @Override
    public int processExpiredSignatures() {
        log.debug("Processing expired signatures");

        List<DigitalSignature> expiredSignatures = signatureRepository.findExpiredSignatures();
        
        for (DigitalSignature signature : expiredSignatures) {
            signature.markAsExpired();
        }

        if (!expiredSignatures.isEmpty()) {
            signatureRepository.saveAll(expiredSignatures);
            log.info("Marked {} signatures as expired", expiredSignatures.size());
        }

        return expiredSignatures.size();
    }

    /**
     * Compute SHA-256 hash of signature value
     */
    private String computeSignatureHash(String signatureValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatureValue.getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Determine why signature is invalid
     */
    private String determineInvalidReason(DigitalSignature signature) {
        if (signature.getStatus() == SignatureStatus.REVOKED) {
            return "Signature has been revoked";
        }
        if (signature.getStatus() == SignatureStatus.EXPIRED) {
            return "Signature has expired";
        }
        if (signature.getStatus() == SignatureStatus.INVALID) {
            return "Signature is marked as invalid";
        }
        if (signature.getExpiresAt() != null && LocalDateTime.now().isAfter(signature.getExpiresAt())) {
            return "Signature expiration date has passed";
        }
        return "Signature status is not SIGNED";
    }

    /**
     * Get current user's display name
     */
    private String getCurrentUserName() {
        try {
            // Try to get from auth service or return default
            UUID userId = authService.getCurrentUserId();
            // In a real implementation, you'd fetch user details here
            return "User-" + userId.toString().substring(0, 8);
        } catch (RuntimeException e) {
            return "System";
        }
    }
}
