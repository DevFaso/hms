package com.example.hms.service.provider;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.provider.ProviderAddressDTO;
import com.example.hms.payload.dto.provider.ProviderBusinessIdentityDTO;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderProfessionalDTO;
import com.example.hms.payload.dto.provider.ProviderResponseDTO;
import com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The provider onboarding of plan US-1 (AC-1 to AC-4, rule 2).
 *
 * <p>What it never does: write a business number, an address or a person's
 * name to a log line or an audit description. Audit rows carry the facility
 * id, the facility type and the verification status only (plan §6.9).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProviderOnboardingServiceImpl implements ProviderOnboardingService {

    static final String MSG_NOT_FOUND = "provider.facility.notFound";
    static final String MSG_TYPE_INVALID = "provider.type.invalid";
    static final String MSG_CODE_DUPLICATE = "provider.code.duplicate";
    static final String MSG_IDENTITY_INCONSISTENT = "provider.identity.inconsistent";
    static final String MSG_LICENCE_DUPLICATE = "provider.licence.duplicate";
    static final String MSG_BUSINESS_DUPLICATE = "provider.business.duplicate";
    static final String MSG_WRONG_STATE = "provider.verification.state";

    static final String REVOKED_REASON = "PROVIDER_VERIFICATION_REVOKED";

    /** The suspensions onboarding imposes itself, and so may lift on VERIFY. */
    private static final Set<String> VERIFICATION_SUSPENSIONS = Set.of(PENDING_VERIFICATION_REASON, REVOKED_REASON);

    private static final String ENTITY_TYPE = "PROVIDER_FACILITY";
    private static final Set<FacilityType> PROVIDER_TYPES = EnumSet.of(FacilityType.PHARMACY, FacilityType.LABORATORY);
    private static final Set<ProviderVerificationStatus> RESUBMITTABLE =
        EnumSet.of(ProviderVerificationStatus.REJECTED, ProviderVerificationStatus.REVOKED);

    private final HospitalRepository hospitalRepository;
    private final ProviderVerificationRepository verificationRepository;
    private final HospitalLifecycleStatusService lifecycleStatusService;
    private final AuditEventLogService auditEventLogService;
    private final RoleValidator roleValidator;
    private final Clock clock;

    @Override
    public ProviderResponseDTO create(ProviderCreateRequestDTO request) {
        requireVerifiedSuperAdmin();
        FacilityType type = request.getFacilityType();
        if (type == null || !PROVIDER_TYPES.contains(type)) {
            throw new BusinessException(MSG_TYPE_INVALID);
        }
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        if (hospitalRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw new ConflictException("code:" + MessageUtil.resolveOrRaw(MSG_CODE_DUPLICATE));
        }
        ProviderBusinessIdentityDTO business = request.getBusiness();

        Hospital facility = Hospital.builder()
            .code(code)
            .email(blankToNull(request.getEmail()))
            .licenseNumber(request.getProfessional().getLicenceNumber().trim())
            .facilityType(type)
            // AC-1: not routable and no login until VERIFY. The lifecycle
            // gate reads lifecycle_state only, so SUSPENDED is what blocks.
            .active(false)
            .lifecycleState(HospitalLifecycleState.SUSPENDED)
            .suspendedAt(Instant.now(clock))
            .suspendedBy(currentActorId())
            .suspensionReason(PENDING_VERIFICATION_REASON)
            .build();
        ProviderVerification verification = new ProviderVerification();
        verification.setStatus(ProviderVerificationStatus.SUBMITTED);
        applyBusiness(verification, business);
        applyProfessional(verification, request.getProfessional());
        applyFacilityIdentity(facility, verification);
        facility = hospitalRepository.save(facility);

        verification.setHospital(facility);
        verification = verificationRepository.save(verification);

        invalidateLifecycleCache();
        audit(facility, AuditEventType.PROVIDER_CREATED, ProviderVerificationStatus.SUBMITTED);
        return toResponse(facility, verification);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProviderResponseDTO> list(FacilityType type, ProviderVerificationStatus status, Pageable pageable) {
        requireVerifiedSuperAdmin();
        Set<FacilityType> types;
        if (type == null) {
            types = PROVIDER_TYPES;
        } else if (PROVIDER_TYPES.contains(type)) {
            types = EnumSet.of(type);
        } else {
            throw new BusinessException(MSG_TYPE_INVALID);
        }
        // Page number and size only: the order is the query's own (newest
        // first, then id), which keeps pages stable. A client sort is not
        // honoured, so no arbitrary property reaches the query either.
        Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return verificationRepository.findLatestByFacilityTypes(types, status, stable)
            .map(v -> toResponse(v.getHospital(), v));
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderResponseDTO get(UUID providerId) {
        requireVerifiedSuperAdmin();
        Hospital facility = loadProvider(providerId);
        return toResponse(facility, currentVerification(facility));
    }

    @Override
    public ProviderResponseDTO verify(UUID providerId, ProviderVerifyRequestDTO request) {
        requireVerifiedSuperAdmin();
        Hospital facility = lockProvider(providerId);
        ProviderVerification verification = currentVerification(facility);
        requireStatus(verification, EnumSet.of(ProviderVerificationStatus.SUBMITTED));
        requirePendingLifecycle(facility);

        ProviderVerifyRequestDTO.Corrections corrections = request == null ? null : request.getCorrections();
        if (corrections != null && corrections.getBusiness() != null) {
            applyBusiness(verification, corrections.getBusiness());
        }
        if (corrections != null && corrections.getProfessional() != null) {
            applyProfessional(verification, corrections.getProfessional());
        }
        // AC-2: the RCCM extract is authoritative and the IFU and CNSS
        // documents must name the same business. Both confirmations, or no
        // verification. Nothing above is saved when this refuses: the
        // transaction rolls back with the exception.
        boolean ifu = request != null && Boolean.TRUE.equals(request.getIfuMatchesRccm());
        boolean cnss = request != null && Boolean.TRUE.equals(request.getCnssMatchesRccm());
        if (!ifu || !cnss) {
            throw new BusinessException(MSG_IDENTITY_INCONSISTENT);
        }
        // AC-3: one business is one facility (v1). The partial unique indexes
        // of V180 hold the same rule against a concurrent verify.
        if (verificationRepository.existsVerifiedLicenceElsewhere(
                facility.getId(), verification.getLicenceAuthority(), verification.getLicenceNumber())) {
            throw new ConflictException(MessageUtil.resolveOrRaw(MSG_LICENCE_DUPLICATE));
        }
        if (verificationRepository.existsVerifiedBusinessElsewhere(
                facility.getId(), verification.getRccmNumber(), verification.getIfuNumber())) {
            throw new ConflictException(MessageUtil.resolveOrRaw(MSG_BUSINESS_DUPLICATE));
        }

        verification.setIfuMatchesRccm(true);
        verification.setCnssMatchesRccm(true);
        verification.setEvidenceNote(blankToNull(request.getEvidenceNote()));
        decide(verification, ProviderVerificationStatus.VERIFIED, null);
        saveVerifiedOrConflict(verification);

        // The facility carries the identity that was just verified: the
        // evidence being verified (a resubmission included), with any
        // corrections applied above. Never the identity of older evidence.
        applyFacilityIdentity(facility, verification);
        facility.setLicenseNumber(verification.getLicenceNumber());
        // VERIFY is the only way a provider becomes ACTIVE (AC-4), both
        // switches together, in this transaction, but only out of a
        // suspension that onboarding itself imposed (pending verification,
        // or a revocation). An operator's suspension (the lifecycle endpoint,
        // with its MFA step-up) outlives the verification: the facility stays
        // SUSPENDED until the lifecycle restore lifts it, with its audit row.
        String reason = facility.getSuspensionReason();
        if (reason != null && VERIFICATION_SUSPENSIONS.contains(reason)) {
            facility.setLifecycleState(HospitalLifecycleState.ACTIVE);
            facility.setActive(true);
            facility.setSuspendedAt(null);
            facility.setSuspendedBy(null);
            facility.setSuspensionReason(null);
        }
        hospitalRepository.save(facility);
        invalidateLifecycleCache();

        audit(facility, AuditEventType.PROVIDER_VERIFIED, ProviderVerificationStatus.VERIFIED);
        return toResponse(facility, verification);
    }

    @Override
    public ProviderResponseDTO reject(UUID providerId, ProviderDecisionRequestDTO request) {
        requireVerifiedSuperAdmin();
        Hospital facility = lockProvider(providerId);
        ProviderVerification verification = currentVerification(facility);
        requireStatus(verification, EnumSet.of(ProviderVerificationStatus.SUBMITTED));
        decide(verification, ProviderVerificationStatus.REJECTED, request.getReason());
        verificationRepository.save(verification);
        // The facility stays SUSPENDED and inactive: it was never verified.
        audit(facility, AuditEventType.PROVIDER_REJECTED, ProviderVerificationStatus.REJECTED);
        return toResponse(facility, verification);
    }

    @Override
    public ProviderResponseDTO resubmit(UUID providerId, ProviderResubmitRequestDTO request) {
        requireVerifiedSuperAdmin();
        Hospital facility = lockProvider(providerId);
        requireStatus(currentVerification(facility), RESUBMITTABLE);

        ProviderVerification next = new ProviderVerification();
        next.setHospital(facility);
        next.setStatus(ProviderVerificationStatus.SUBMITTED);
        applyBusiness(next, request.getBusiness());
        applyProfessional(next, request.getProfessional());
        next = verificationRepository.save(next);
        audit(facility, AuditEventType.PROVIDER_EVIDENCE_RESUBMITTED, ProviderVerificationStatus.SUBMITTED);
        return toResponse(facility, next);
    }

    @Override
    public ProviderResponseDTO revoke(UUID providerId, ProviderDecisionRequestDTO request) {
        requireVerifiedSuperAdmin();
        Hospital facility = lockProvider(providerId);
        ProviderVerification verification = currentVerification(facility);
        requireStatus(verification, EnumSet.of(ProviderVerificationStatus.VERIFIED));
        decide(verification, ProviderVerificationStatus.REVOKED, request.getReason());
        verificationRepository.save(verification);

        // Rule 2: the effects of a suspension, and never more. An ACTIVE
        // provider becomes SUSPENDED. One already SUSPENDED keeps its
        // suspension record. One ARCHIVED, purge-scheduled or purged stays
        // exactly there: moving it back to SUSPENDED would silently cancel
        // the archive or the purge, which only the lifecycle endpoints may
        // do, with their own audit rows and step-up.
        if (facility.getLifecycleState() == HospitalLifecycleState.ACTIVE) {
            facility.setLifecycleState(HospitalLifecycleState.SUSPENDED);
            facility.setSuspendedAt(Instant.now(clock));
            facility.setSuspendedBy(currentActorId());
            facility.setSuspensionReason(REVOKED_REASON);
        }
        facility.setActive(false);
        hospitalRepository.save(facility);
        invalidateLifecycleCache();

        audit(facility, AuditEventType.PROVIDER_VERIFICATION_REVOKED, ProviderVerificationStatus.REVOKED);
        return toResponse(facility, verification);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void requireVerifiedSuperAdmin() {
        if (!roleValidator.isSuperAdminFromJwtClaim()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /**
     * The provider for a state transition, its row locked (PESSIMISTIC_WRITE)
     * before any status is read. Verify, reject, resubmit and revoke of one
     * provider, and its lifecycle restore, therefore run one at a time, and
     * the verification read that follows sees what the previous one
     * committed. The hospital row is the only lock these paths take.
     */
    private Hospital lockProvider(UUID providerId) {
        return hospitalRepository.findByIdForUpdate(providerId)
            .filter(Hospital::isProvider)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
    }

    /** A facility that is not a provider answers exactly as an unknown id. */
    private Hospital loadProvider(UUID providerId) {
        return hospitalRepository.findById(providerId)
            .filter(Hospital::isProvider)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
    }

    private ProviderVerification currentVerification(Hospital facility) {
        return verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(facility.getId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
    }

    /**
     * VERIFY activates a provider that is waiting for it, and nothing else:
     * the facility must be SUSPENDED. An ARCHIVED or purge-scheduled provider
     * comes back only through the lifecycle restore (with its MFA step-up and
     * its own audit rows), never as a side effect of a verification (409).
     */
    private static void requirePendingLifecycle(Hospital facility) {
        if (facility.getLifecycleState() != HospitalLifecycleState.SUSPENDED) {
            throw new ConflictException(MessageUtil.resolveOrRaw(MSG_WRONG_STATE, facility.getLifecycleState()));
        }
    }

    /** A transition from the wrong state is a conflict (409): another decision got there first. */
    private static void requireStatus(ProviderVerification verification, Set<ProviderVerificationStatus> allowed) {
        if (!allowed.contains(verification.getStatus())) {
            throw new ConflictException(MessageUtil.resolveOrRaw(MSG_WRONG_STATE, verification.getStatus()));
        }
    }

    private void decide(ProviderVerification verification, ProviderVerificationStatus status, String reason) {
        verification.setStatus(status);
        verification.setDecidedByUserId(currentActorId());
        verification.setDecidedAt(LocalDateTime.now(clock));
        verification.setDecisionReason(reason == null ? null : reason.trim());
    }

    /**
     * Flush now, so a concurrent verify that slipped past the existence checks
     * meets V180's partial unique indexes here and answers 409, not a generic
     * 400 from the integrity handler.
     */
    private void saveVerifiedOrConflict(ProviderVerification verification) {
        try {
            verificationRepository.saveAndFlush(verification);
        } catch (DataIntegrityViolationException ex) {
            String detail = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            String key = detail.contains("uq_provider_licence_verified") ? MSG_LICENCE_DUPLICATE : MSG_BUSINESS_DUPLICATE;
            throw new ConflictException(MessageUtil.resolveOrRaw(key));
        }
    }

    private static void applyBusiness(ProviderVerification v, ProviderBusinessIdentityDTO business) {
        ProviderAddressDTO address = business.getAddress();
        v.setLegalName(business.getLegalName().trim());
        v.setTradeName(blankToNull(business.getTradeName()));
        v.setLegalStructure(business.getLegalStructure().trim());
        v.setRccmNumber(identifier(business.getRccmNumber()));
        v.setIfuNumber(identifier(business.getIfuNumber()));
        v.setCnssNumber(identifier(business.getCnssNumber()));
        v.setAddressSecteur(blankToNull(address.getSecteur()));
        v.setAddressSection(blankToNull(address.getSection()));
        v.setAddressLot(blankToNull(address.getLot()));
        v.setAddressParcelle(blankToNull(address.getParcelle()));
        v.setAddressCity(address.getCity().trim());
        v.setAddressRegion(address.getRegion().trim());
        v.setCompanyPhone(business.getCompanyPhone().trim());
        v.setManagerName(business.getManagerName().trim());
        v.setManagerTitle(business.getManagerTitle().trim());
        v.setBusinessStartedOn(business.getStartedOn());
    }

    /** The facility row shows the business as its evidence names it: name, phone, address. */
    private static void applyFacilityIdentity(Hospital facility, ProviderVerification v) {
        facility.setName(v.getTradeName() != null ? v.getTradeName() : v.getLegalName());
        facility.setPhoneNumber(v.getCompanyPhone());
        facility.setAddress(streetAddress(v));
        facility.setCity(v.getAddressCity());
        facility.setRegion(v.getAddressRegion());
    }

    private static void applyProfessional(ProviderVerification v, ProviderProfessionalDTO professional) {
        v.setLicenceNumber(identifier(professional.getLicenceNumber()));
        // The authority is half of the AC-3 uniqueness key, so it is held in
        // the same single spelling as the number: "DGPML" and " dgpml " are
        // one authority, for the duplicate check and for V180's index alike.
        v.setLicenceAuthority(identifier(professional.getLicenceAuthority()));
        v.setLicenceIssuedOn(professional.getLicenceIssuedOn());
        v.setLicenceExpiresOn(professional.getLicenceExpiresOn());
        v.setResponsibleProfessionalName(professional.getResponsibleName().trim());
        v.setResponsibleProfessionalRegistration(identifier(professional.getResponsibleOrdreNumber()));
    }

    /**
     * A registration number in one spelling, so the duplicate checks and the
     * unique indexes compare like with like: trimmed, inner whitespace
     * collapsed, upper-case.
     */
    static String identifier(String raw) {
        return raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String streetAddress(ProviderVerification v) {
        String joined = Stream.of(
                labelled("Secteur", v.getAddressSecteur()),
                labelled("Section", v.getAddressSection()),
                labelled("Lot", v.getAddressLot()),
                labelled("Parcelle", v.getAddressParcelle()))
            .filter(part -> !part.isEmpty())
            .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static String labelled(String label, String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? "" : label + " " + trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UUID currentActorId() {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        return context.getPrincipalUserId();
    }

    private void invalidateLifecycleCache() {
        try {
            lifecycleStatusService.invalidate();
        } catch (RuntimeException ex) {
            // Best-effort, as in HospitalLifecycleServiceImpl: a stale cache
            // only delays the new state by its TTL (30 s).
            log.warn("[PROVIDER-ONBOARDING] Lifecycle cache invalidation failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Ids, the facility type and the status only (plan §6.9): no number, no name. */
    private void audit(Hospital facility, AuditEventType type, ProviderVerificationStatus status) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(context.getPrincipalUserId())
                .userName(context.getPrincipalUsername())
                .eventType(type)
                .eventDescription("Provider facility " + FacilityType.orHospital(facility.getFacilityType())
                    + " verification " + status)
                .resourceId(facility.getId().toString())
                .entityType(ENTITY_TYPE)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[PROVIDER-ONBOARDING] Failed to record audit event {} for facility {}",
                type, facility.getId(), ex);
        }
    }

    private static ProviderResponseDTO toResponse(Hospital facility, ProviderVerification v) {
        ProviderResponseDTO.ProviderResponseDTOBuilder dto = ProviderResponseDTO.builder()
            .id(facility.getId())
            .facilityType(facility.getFacilityType())
            .code(facility.getCode())
            .name(facility.getName())
            .email(facility.getEmail())
            .active(facility.isActive())
            .lifecycleState(facility.getLifecycleState());
        if (v == null) {
            return dto.build();
        }
        return dto
            .verificationId(v.getId())
            .verificationStatus(v.getStatus())
            .business(ProviderBusinessIdentityDTO.builder()
                .legalName(v.getLegalName())
                .tradeName(v.getTradeName())
                .legalStructure(v.getLegalStructure())
                .rccmNumber(v.getRccmNumber())
                .ifuNumber(v.getIfuNumber())
                .cnssNumber(v.getCnssNumber())
                .address(ProviderAddressDTO.builder()
                    .secteur(v.getAddressSecteur())
                    .section(v.getAddressSection())
                    .lot(v.getAddressLot())
                    .parcelle(v.getAddressParcelle())
                    .city(v.getAddressCity())
                    .region(v.getAddressRegion())
                    .build())
                .companyPhone(v.getCompanyPhone())
                .managerName(v.getManagerName())
                .managerTitle(v.getManagerTitle())
                .startedOn(v.getBusinessStartedOn())
                .build())
            .professional(ProviderProfessionalDTO.builder()
                .licenceNumber(v.getLicenceNumber())
                .licenceAuthority(v.getLicenceAuthority())
                .licenceIssuedOn(v.getLicenceIssuedOn())
                .licenceExpiresOn(v.getLicenceExpiresOn())
                .responsibleName(v.getResponsibleProfessionalName())
                .responsibleOrdreNumber(v.getResponsibleProfessionalRegistration())
                .build())
            .ifuMatchesRccm(v.isIfuMatchesRccm())
            .cnssMatchesRccm(v.isCnssMatchesRccm())
            .evidenceNote(v.getEvidenceNote())
            .decidedByUserId(v.getDecidedByUserId())
            .decidedAt(v.getDecidedAt())
            .decisionReason(v.getDecisionReason())
            .submittedAt(v.getCreatedAt())
            .build();
    }
}
