package com.example.hms.service.provider;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.provider.ProviderDirectoryEntryDTO;
import com.example.hms.payload.dto.provider.ProviderDirectoryPageDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.provider.RoleFacilityCompatibility;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.support.UserAccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The provider directory (provider plan §6.5, AC-14, T9).
 *
 * <ul>
 *   <li><b>Who.</b> The roles of {@link SecurityConstants#PROVIDER_DIRECTORY_AUTHORITIES}
 *       (the annotation and the {@code SecurityConfig} matcher read it), held
 *       LIVE at the hospital the request acts at, which must be a HOSPITAL: a
 *       directory is for the facilities that route to providers. A verified
 *       super-admin acting at a hospital passes; in global view they get the
 *       usual "select a hospital" refusal.</li>
 *   <li><b>What.</b> Facilities whose current verification is VERIFIED and
 *       that are active and ACTIVE in their lifecycle: an unverified,
 *       rejected, revoked or suspended provider is never offered.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProviderDirectoryServiceImpl implements ProviderDirectoryService {

    static final String MSG_TYPE_INVALID = "provider.type.invalid";
    private static final int MAX_QUERY_LENGTH = 100;
    private static final Set<FacilityType> PROVIDER_TYPES = EnumSet.of(FacilityType.PHARMACY, FacilityType.LABORATORY);

    /** The directory roles, bare ({@code DOCTOR}), from the one list the annotation and the matcher read. */
    private static final Set<String> DIRECTORY_ROLES =
        Arrays.stream(SecurityConstants.authorities(SecurityConstants.PROVIDER_DIRECTORY_AUTHORITIES))
            .map(RoleFacilityCompatibility::bare)
            .collect(Collectors.toUnmodifiableSet());

    private final ProviderOrganisationsFlag organisationsFlag;
    private final ActingScopeResolver actingScopeResolver;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ProviderVerificationRepository verificationRepository;

    @Override
    public ProviderDirectoryPageDTO search(String type, String query) {
        // The flag first (AC-14): off, the directory is empty for everyone,
        // whatever they send.
        if (!organisationsFlag.isEnabled()) {
            return ProviderDirectoryPageDTO.empty();
        }
        // Who next, then what: a refused caller gets the same refusal
        // whatever they send, never a 400 that depends on the parameters.
        UUID actingHospitalId = actingScopeResolver.requirePinned();
        requireDirectoryReader(actingHospitalId);
        Set<FacilityType> types = parseTypes(type);
        // One more than the cap tells whether more matched.
        List<ProviderDirectoryEntryDTO> found = verificationRepository
            .findDirectory(types, namePattern(query), PageRequest.of(0, MAX_RESULTS + 1))
            .stream()
            .map(ProviderDirectoryServiceImpl::toEntry)
            .toList();
        boolean hasMore = found.size() > MAX_RESULTS;
        return new ProviderDirectoryPageDTO(hasMore ? found.subList(0, MAX_RESULTS) : found, hasMore);
    }

    /**
     * The acting facility is a HOSPITAL, and the caller holds a directory role
     * there, live (or is a verified super-admin, who holds no assignment
     * there). A provider facility never reads the directory: its users are
     * confined anyway, and a super-admin naming one is refused here.
     */
    private void requireDirectoryReader(UUID actingHospitalId) {
        if (hospitalRepository.findClinicalById(actingHospitalId).isEmpty()) {
            throw new AccessDeniedException("Access denied");
        }
        if (actingScopeResolver.isVerifiedSuperAdmin()) {
            return;
        }
        UUID userId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        boolean holdsRole = userId != null && assignmentRepository.findByUser_IdAndActiveTrue(userId).stream()
            .filter(row -> row.getHospital() != null && actingHospitalId.equals(row.getHospital().getId()))
            // A surgeon or a physician IS a doctor, as the annotation sees them.
            .anyMatch(row -> UserAccountAccess.expandedCodes(row.getRole()).stream()
                .anyMatch(DIRECTORY_ROLES::contains));
        if (!holdsRole) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private static Set<FacilityType> parseTypes(String raw) {
        FacilityType parsed;
        try {
            parsed = FacilityType.fromParameter(raw);
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(MSG_TYPE_INVALID);
        }
        if (parsed == null) {
            return PROVIDER_TYPES;
        }
        if (!PROVIDER_TYPES.contains(parsed)) {
            throw new BusinessException(MSG_TYPE_INVALID);
        }
        return EnumSet.of(parsed);
    }

    /** Lower-case, LIKE-escaped, wrapped in {@code %}; {@code null} for no filter. */
    static String namePattern(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
        }
        String escaped = trimmed.toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static ProviderDirectoryEntryDTO toEntry(ProviderVerification verification) {
        Hospital facility = verification.getHospital();
        return ProviderDirectoryEntryDTO.builder()
            .id(facility.getId())
            .name(facility.getName())
            .city(facility.getCity())
            .phone(facility.getPhoneNumber())
            .licenceNumber(verification.getLicenceNumber())
            .facilityType(facility.getFacilityType())
            .build();
    }
}
