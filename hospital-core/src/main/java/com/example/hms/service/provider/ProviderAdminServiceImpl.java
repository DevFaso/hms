package com.example.hms.service.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.provider.ProviderProfileDTO;
import com.example.hms.payload.dto.provider.ProviderProfileUpdateDTO;
import com.example.hms.payload.dto.provider.ProviderSettingsDTO;
import com.example.hms.payload.dto.provider.ProviderStaffMemberDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.provider.RoleFacilityCompatibility;
import com.example.hms.service.UserRoleHospitalAssignmentService;
import com.example.hms.service.support.UserAccountAccess;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The provider admin pages (provider plan US-2, AC-6, §6.5).
 *
 * <p>The staff changes go through the assignment service's own
 * {@code deactivateProviderStaffAssignments} and
 * {@code regenerateProviderStaffAssignmentCodes}, so they
 * carry exactly the rules a hospital admin's changes carry (the row must be
 * one the caller may change: at a facility they administer, never an admin
 * role's row, never a super-admin's account; the invitation revoked with the
 * row; activation only through the holder's code). This class only decides
 * WHICH rows: the member's rows at the caller's own facility.
 *
 * <p>Logs carry ids only: no name, no phone, no email (plan §6.9).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProviderAdminServiceImpl implements ProviderAdminService {

    private static final String PATIENT = "PATIENT";

    private final ProviderSeatResolver seatResolver;
    private final ProviderOrganisationsFlag organisationsFlag;
    private final HospitalRepository hospitalRepository;
    private final ProviderVerificationRepository verificationRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRoleHospitalAssignmentService assignmentService;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderProfileDTO> getProfile() {
        return seatResolver.current().map(this::toProfile);
    }

    @Override
    public Optional<ProviderProfileDTO> updateProfile(String rawBody) {
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderSeat seat = found.get();
        ProviderProfileUpdateDTO request = readBody(rawBody);
        // Trimmed first, so a blank optional field means "clear it", not an invalid value.
        ProviderProfileUpdateDTO body = ProviderProfileUpdateDTO.builder()
            .phoneNumber(request == null ? null : blankToNull(request.getPhoneNumber()))
            .email(request == null ? null : blankToNull(request.getEmail()))
            .website(request == null ? null : blankToNull(request.getWebsite()))
            .build();
        Set<ConstraintViolation<ProviderProfileUpdateDTO>> violations = validator.validate(body);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        Hospital facility = seat.facility();
        facility.setPhoneNumber(body.getPhoneNumber());
        facility.setEmail(body.getEmail());
        facility.setWebsite(body.getWebsite());
        hospitalRepository.save(facility);
        log.info("[PROVIDER-ADMIN] Facility {} contact updated by user {}", facility.getId(), seat.userId());
        return Optional.of(toProfile(seat));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<List<ProviderStaffMemberDTO>> listStaff() {
        return seatResolver.currentAdmin().map(seat -> staffRowsByUser(seat.facility().getId()).values().stream()
            .map(rows -> toMember(rows, seat))
            .flatMap(Optional::stream)
            .sorted(Comparator
                .comparing((ProviderStaffMemberDTO m) -> lower(m.getLastName()))
                .thenComparing(m -> lower(m.getFirstName()))
                .thenComparing(m -> lower(m.getUsername())))
            .toList());
    }

    @Override
    public Optional<ProviderStaffMemberDTO> deactivateStaff(String userId) {
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderSeat seat = found.get();
        UUID memberId = parseId(userId);
        Optional<List<UserRoleHospitalAssignment>> rows = manageableRows(seat, memberId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // One scope read for the member's rows, all checked before any changes.
        assignmentService.deactivateProviderStaffAssignments(idsOf(rows.get()));
        log.info("[PROVIDER-ADMIN] Staff {} deactivated at facility {} by user {}",
            memberId, seat.facility().getId(), seat.userId());
        // The rows just changed (the same managed instances), never a second read.
        return toMember(rows.get(), seat);
    }

    @Override
    public Optional<ProviderStaffMemberDTO> activateStaff(String userId) {
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderSeat seat = found.get();
        UUID memberId = parseId(userId);
        Optional<List<UserRoleHospitalAssignment>> rows = manageableRows(seat, memberId);
        // A disabled account stays disabled: entering the new code would
        // switch it back on (and clear its lockout), and only a super-admin
        // re-enables an account.
        if (rows.isEmpty() || !rows.get().get(0).getUser().isActive()) {
            return Optional.empty();
        }
        List<UUID> inactive = rows.get().stream()
            .filter(row -> !Boolean.TRUE.equals(row.getActive()))
            .map(UserRoleHospitalAssignment::getId)
            .toList();
        if (!inactive.isEmpty()) {
            assignmentService.regenerateProviderStaffAssignmentCodes(inactive, true);
        }
        log.info("[PROVIDER-ADMIN] Staff {} re-invited at facility {} ({} assignment(s)) by user {}",
            memberId, seat.facility().getId(), inactive.size(), seat.userId());
        return toMember(rows.get(), seat);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderSettingsDTO> getSettings() {
        return seatResolver.current().map(seat -> ProviderSettingsDTO.builder()
            .facilityId(seat.facility().getId())
            .facilityType(seat.facility().getFacilityType())
            .providerAdmin(seat.admin())
            .organisationsEnabled(organisationsFlag.isEnabled())
            .build());
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * The member's rows at the caller's facility, or empty when the member is
     * out of the caller's reach: unknown, deleted, holding no staff row here,
     * the caller, or holding an admin role (PROVIDER_ADMIN, HOSPITAL_ADMIN,
     * ADMIN, SUPER_ADMIN) anywhere, active or not, by assignment OR by global
     * role ({@link UserAccountAccess#holdsAdminRole}, the rule that shields a
     * super-admin's account from a hospital admin). Admins are administered by
     * the platform, never by a peer. Decided before anything changes, so such
     * a member gets the unmapped answer and nothing is touched.
     */
    private Optional<List<UserRoleHospitalAssignment>> manageableRows(ProviderSeat seat, UUID userId) {
        if (userId == null || userId.equals(seat.userId())) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> everyRow = assignmentRepository.findByUserId(userId);
        List<UserRoleHospitalAssignment> here = everyRow.stream()
            .filter(row -> row.getHospital() != null && seat.facility().getId().equals(row.getHospital().getId()))
            .filter(row -> !PATIENT.equals(UserAccountAccess.roleCode(row.getRole())))
            .toList();
        if (here.isEmpty()) {
            return Optional.empty();
        }
        User member = here.get(0).getUser();
        if (member == null || member.isDeleted() || UserAccountAccess.holdsAdminRole(member, everyRow)) {
            return Optional.empty();
        }
        return Optional.of(here);
    }

    private static List<UUID> idsOf(List<UserRoleHospitalAssignment> rows) {
        return rows.stream().map(UserRoleHospitalAssignment::getId).toList();
    }

    /** Staff rows at the facility (no PATIENT row, no deleted account), grouped by holder in a stable order. */
    private Map<UUID, List<UserRoleHospitalAssignment>> staffRowsByUser(UUID facilityId) {
        Map<UUID, List<UserRoleHospitalAssignment>> byUser = new LinkedHashMap<>();
        for (UserRoleHospitalAssignment row : assignmentRepository.findStaffRowsByHospitalId(facilityId)) {
            User holder = row.getUser();
            if (holder == null || holder.getId() == null || holder.isDeleted()
                    || PATIENT.equals(UserAccountAccess.roleCode(row.getRole()))) {
                continue;
            }
            byUser.computeIfAbsent(holder.getId(), id -> new ArrayList<>()).add(row);
        }
        return byUser;
    }

    /** One member from their rows here; empty when there are none (never an all-null member). */
    private static Optional<ProviderStaffMemberDTO> toMember(List<UserRoleHospitalAssignment> rows, ProviderSeat seat) {
        User holder = rows.isEmpty() ? null : rows.get(0).getUser();
        if (holder == null) {
            return Optional.empty();
        }
        TreeSet<String> roles = new TreeSet<>();
        boolean active = false;
        boolean pending = false;
        for (UserRoleHospitalAssignment row : rows) {
            String role = UserAccountAccess.roleCode(row.getRole());
            if (!role.isEmpty()) {
                roles.add(role);
            }
            boolean rowActive = Boolean.TRUE.equals(row.getActive());
            active |= rowActive;
            pending |= !rowActive && row.getConfirmationCode() != null && row.getConfirmationVerifiedAt() == null;
        }
        return Optional.of(ProviderStaffMemberDTO.builder()
            .userId(holder.getId())
            .username(holder.getUsername())
            .firstName(holder.getFirstName())
            .lastName(holder.getLastName())
            .email(holder.getEmail())
            .roles(List.copyOf(roles))
            .active(active)
            .invitationPending(pending)
            .providerAdmin(roles.contains(RoleFacilityCompatibility.PROVIDER_ADMIN))
            .self(Objects.equals(holder.getId(), seat.userId()))
            .build());
    }

    private ProviderProfileDTO toProfile(ProviderSeat seat) {
        Hospital facility = seat.facility();
        ProviderProfileDTO.ProviderProfileDTOBuilder dto = ProviderProfileDTO.builder()
            .id(facility.getId())
            .facilityType(FacilityType.orHospital(facility.getFacilityType()))
            .code(facility.getCode())
            .phoneNumber(facility.getPhoneNumber())
            .email(facility.getEmail())
            .website(facility.getWebsite())
            .editable(seat.admin());
        // The current state of the evidence (SUBMITTED during a resubmission,
        // REVOKED...), but the identity only as the platform VERIFIED it:
        // never a submitted, rejected or revoked one. None verified: no identity.
        verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(facility.getId())
            .ifPresent(latest -> dto.verificationStatus(latest.getStatus()));
        verificationRepository.findFirstByHospital_IdAndStatusOrderByCreatedAtDesc(facility.getId(),
                ProviderVerificationStatus.VERIFIED)
            .ifPresent(verified -> applyVerifiedIdentity(dto, facility, verified));
        return dto.build();
    }

    /**
     * The identity as verified. The registered street address is the
     * facility row's, which VERIFY itself wrote from this evidence (nothing
     * else changes it while the evidence stays VERIFIED).
     */
    private static void applyVerifiedIdentity(ProviderProfileDTO.ProviderProfileDTOBuilder dto, Hospital facility,
                                              ProviderVerification v) {
        dto.name(v.getTradeName() != null ? v.getTradeName() : v.getLegalName())
            .address(facility.getAddress())
            .city(v.getAddressCity())
            .region(v.getAddressRegion())
            .legalName(v.getLegalName())
            .tradeName(v.getTradeName())
            .licenceNumber(v.getLicenceNumber())
            .licenceAuthority(v.getLicenceAuthority())
            .companyPhone(v.getCompanyPhone())
            .verifiedAt(v.getDecidedAt());
    }

    /**
     * The request body, read only once the caller is known to be the
     * facility's admin: Jackson never ran before the seat check, so a
     * malformed body cannot tell anyone else the page exists. Unknown
     * properties are ignored (the configured mapper's rule), so the verified
     * identity cannot be sent; a body that is not a JSON object is a 400.
     */
    private ProviderProfileUpdateDTO readBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, ProviderProfileUpdateDTO.class);
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("Malformed request body.");
        }
    }

    /** The raw path segment as a user id; {@code null} (answered as unknown) when it is not one. */
    private static UUID parseId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
