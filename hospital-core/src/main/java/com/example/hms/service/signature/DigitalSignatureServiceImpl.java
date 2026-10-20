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
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;

/**
 * Implementation of digital signature service.
 * Story #17: Generic Report Signing API
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DigitalSignatureServiceImpl implements DigitalSignatureService {
    private static final String SIGNATURE_NOT_FOUND_KEY = "signature.notFound";


    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private final DigitalSignatureRepository signatureRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final DigitalSignatureMapper signatureMapper;
    private final AuthService authService;
    private final RoleValidator roleValidator;
    private final MessageSource messageSource;
    private final SignableReportLocator reportLocator;
    private final com.example.hms.repository.UserRepository userRepository;

    private static String normaliseName(String value) {
        return value == null ? "" : value.trim().replaceAll("\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean namesMatch(String typed, String fullName) {
        String expected = normaliseName(fullName);
        return !expected.isEmpty() && expected.equals(normaliseName(typed));
    }

    @Override
    public SignatureResponseDTO signReport(SignatureRequestDTO request) {
        log.debug("Creating digital signature for report type: {}, ID: {}", 
            request.getReportType(), request.getReportId());

        // QA D71: the signer, the hospital and the report on the request are claims.
        // The signer is the caller's own staff record in the hospital they act in;
        // the report must exist and belong to that hospital; the signature value
        // must be the signer's name.
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId == null) {
            throw new AccessDeniedException("A signature must be made in a hospital context.");
        }
        if (!activeHospitalId.equals(request.getHospitalId())) {
            throw new AccessDeniedException("You can only sign in the hospital you are acting in.");
        }
        UUID currentUserId = authService.getCurrentUserId();
        Staff staff = (currentUserId == null ? java.util.Optional.<Staff>empty()
                : staffRepository.findByUserIdAndHospitalId(currentUserId, activeHospitalId))
            .orElseThrow(() -> new AccessDeniedException("No staff record for you in this hospital."));
        if (!staff.getId().equals(request.getSignedByStaffId())) {
            throw new AccessDeniedException("You can only sign in your own name.");
        }
        UUID reportHospitalId = reportLocator.owningHospitalId(request.getReportType(), request.getReportId())
            .orElseThrow(() -> new ResourceNotFoundException("signature.report.notFound", request.getReportId()));
        if (!activeHospitalId.equals(reportHospitalId)) {
            // 404, not 403: do not confirm that another hospital's record exists.
            throw new ResourceNotFoundException("signature.report.notFound", request.getReportId());
        }
        if (!namesMatch(request.getSignatureValue(), staff.getFullName())) {
            throw new BusinessException(text("signature.value.mustMatchSigner", LocaleContextHolder.getLocale()));
        }

        Hospital hospital = hospitalRepository.findClinicalById(activeHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", activeHospitalId));

        // Compute signature hash
        String signatureHash = computeSignatureHash(request.getSignatureValue());

        // Check for duplicate active signatures
        boolean hasSigned = signatureRepository.existsSignedByStaff(
            request.getReportId(), request.getReportType(), staff.getId());
        if (hasSigned) {
            throw new BusinessException("signature.r8.alreadySigned");
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
            DigitalSignature signature = loadInActingHospital(request.getSignatureId());
            signatures = List.of(signature);
        } else {
            // Find all signatures for report
            signatures = inActingHospital(signatureRepository.findActiveSignaturesByReportId(
                request.getReportId(), request.getReportType()));
        }

        // The verdict is read by whoever asked for the verification.
        Locale locale = LocaleContextHolder.getLocale();
        if (signatures.isEmpty()) {
            return SignatureVerificationResponseDTO.builder()
                .isValid(false)
                .message(text("signature.verify.none", locale))
                .invalidReason(text("signature.verify.none.reason", locale))
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

                String message = text(
                    isValid ? "signature.verify.success" : "signature.verify.invalid", locale);
                String invalidReason = isValid ? null : determineInvalidReason(signature, locale);

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
            .message(text("signature.verify.failed", locale))
            .invalidReason(text("signature.verify.failed.reason", locale))
            .verifiedAt(LocalDateTime.now())
            .build();
    }

    @Override
    public SignatureResponseDTO revokeSignature(UUID signatureId, SignatureRevocationRequestDTO request) {
        log.debug("Revoking signature: {}", signatureId);

        DigitalSignature signature = signatureRepository.findById(signatureId)
            .orElseThrow(() -> new ResourceNotFoundException(SIGNATURE_NOT_FOUND_KEY, signatureId));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(signature.getHospital(), SIGNATURE_NOT_FOUND_KEY, signatureId);

        if (!signature.canRevoke()) {
            // QA D528: a key, not "Current status: REVOKED" in English.
            throw new BusinessException("signature.r8.notRevocable");
        }

        UUID currentUserId = authService.getCurrentUserId();
        // QA D502: a signature is the signer's. Only the signer revokes it, or
        // an administrator of the hospital it was made in (the tenant check
        // above), and the revocation always carries a reason in the audit log.
        // Another clinician is refused, whatever their role. The admin must be
        // signed in as admin AND hold HOSPITAL_ADMIN at the signature's own
        // hospital: an admin role elsewhere does not count.
        boolean isSigner = currentUserId != null && signature.getSignedBy() != null
            && signature.getSignedBy().getUser() != null
            && currentUserId.equals(signature.getSignedBy().getUser().getId());
        boolean isAdmin = com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()
            || (roleValidator.isHospitalAdminFromAuthGlobalOnly()
                && currentUserId != null && signature.getHospital() != null
                && roleValidator.isHospitalAdmin(currentUserId, signature.getHospital().getId()));
        if (!isSigner && !isAdmin) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                text("signature.revoke.notSigner", LocaleContextHolder.getLocale()));
        }
        String currentUserName = getCurrentUserName();

        signature.revoke(currentUserId, currentUserName, request.getRevocationReason(),
            request.getIpAddress(), request.getDeviceInfo());

        DigitalSignature saved = signatureRepository.save(signature);

        log.info("Signature revoked successfully: {}, Reason: {}", signatureId, request.getRevocationReason());

        return signatureMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureResponseDTO> getAllSignatures() {
        // ── Tenant isolation: hospital admins list their own hospital's signatures;
        //    only super-admin (null) sees all hospitals ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<DigitalSignature> signatures = activeHospitalId != null
            ? signatureRepository.findByHospital_IdOrderBySignatureDateTimeDesc(activeHospitalId)
            : signatureRepository.findAll();
        log.debug("Fetching signatures for admin listing (hospital scope: {})",
            activeHospitalId != null ? activeHospitalId : "ALL");
        return signatures.stream()
            .map(signatureMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureResponseDTO> getSignaturesByReportId(SignatureType reportType, UUID reportId) {
        log.debug("Fetching signatures for report type: {}, ID: {}", reportType, reportId);

        List<DigitalSignature> signatures = inActingHospital(signatureRepository
            .findByReportIdAndReportTypeOrderBySignatureDateTimeDesc(reportId, reportType));

        return signatures.stream()
            .map(signatureMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureResponseDTO> getSignaturesByProvider(UUID providerId) {
        log.debug("Fetching signatures for provider: {}", providerId);

        List<DigitalSignature> signatures = inActingHospital(signatureRepository
            .findBySignedBy_IdOrderBySignatureDateTimeDesc(providerId));

        return signatures.stream()
            .map(signatureMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SignatureResponseDTO getSignatureById(UUID signatureId) {
        log.debug("Fetching signature: {}", signatureId);

        return signatureMapper.toResponseDTO(loadInActingHospital(signatureId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignatureAuditEntryDTO> getSignatureAuditTrail(UUID signatureId) {
        log.debug("Fetching audit trail for signature: {}", signatureId);

        DigitalSignature signature = loadInActingHospital(signatureId);

        return signatureMapper.toAuditEntryDTOs(signature.getAuditLog());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReportSigned(SignatureType reportType, UUID reportId) {
        // QA D507: another hospital's signature does not answer "signed".
        return inActingHospital(signatureRepository
            .findByReportIdAndReportTypeOrderBySignatureDateTimeDesc(reportId, reportType))
            .stream().anyMatch(DigitalSignature::isValid);
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
    private String text(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }

    private String determineInvalidReason(DigitalSignature signature, Locale locale) {
        if (signature.getStatus() == SignatureStatus.REVOKED) {
            return text("signature.invalid.revoked", locale);
        }
        if (signature.getStatus() == SignatureStatus.EXPIRED) {
            return text("signature.invalid.expired", locale);
        }
        if (signature.getStatus() == SignatureStatus.INVALID) {
            return text("signature.invalid.marked", locale);
        }
        if (signature.getExpiresAt() != null && LocalDateTime.now().isAfter(signature.getExpiresAt())) {
            return text("signature.invalid.expiryPassed", locale);
        }
        return text("signature.invalid.notSigned", locale);
    }

    /**
     * Get current user's display name
     */
    private String getCurrentUserName() {
        // QA D502: the audit trail names the person, never "User-xxxxxxxx".
        UUID userId = authService.getCurrentUserId();
        if (userId == null) {
            return "System";
        }
        return userRepository.findById(userId)
            .map(user -> {
                String name = java.util.stream.Stream.of(user.getFirstName(), user.getLastName())
                    .filter(part -> part != null && !part.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
                return name.isBlank() ? user.getUsername() : name;
            })
            .orElse("System");
    }

    /**
     * QA D507: signature reads answer only for the hospital the caller acts at
     * (a verified super-admin in global view reads every hospital). A row of
     * another hospital is left out of a list, and answered as a missing id by
     * a single read.
     */
    private List<DigitalSignature> inActingHospital(List<DigitalSignature> signatures) {
        UUID acting = com.example.hms.security.tenant.TenantOwnership.actingHospitalIdOrNullForGlobal();
        if (acting == null) {
            return signatures;
        }
        return signatures.stream()
            .filter(s -> s.getHospital() != null && acting.equals(s.getHospital().getId()))
            .toList();
    }

    private DigitalSignature loadInActingHospital(UUID signatureId) {
        DigitalSignature signature = signatureRepository.findById(signatureId)
            .orElseThrow(() -> new ResourceNotFoundException(SIGNATURE_NOT_FOUND_KEY, signatureId));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
            signature.getHospital(), SIGNATURE_NOT_FOUND_KEY, signatureId);
        return signature;
    }
}
