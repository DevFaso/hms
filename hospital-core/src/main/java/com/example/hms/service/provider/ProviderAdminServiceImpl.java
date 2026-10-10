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
 * {@code deactivateAssignment} and {@code regenerateAssignmentCode}, so they
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

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderProfileDTO> getProfile() {
        return seatResolver.current().map(this::toProfile);
    }

    @Override
    public Optional<ProviderProfileDTO> updateProfile(ProviderProfileUpdateDTO request) {
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderSeat seat = found.get();
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
        return seatResolver.currentAdmin().map(seat -> {
            List<ProviderStaffMemberDTO> members = new ArrayList<>();
            for (List<UserRoleHospitalAssignment> rows : staffRowsByUser(seat.facility().getId()).values()) {
                members.add(toMember(rows, seat));
            }
            members.sort(Comparator
                .comparing((ProviderStaffMemberDTO m) -> lower(m.getLastName()))
                .thenComparing(m -> lower(m.getFirstName()))
                .thenComparing(m -> lower(m.getUsername())));
            return members;
        });
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
        for (UserRoleHospitalAssignment row : rows.get()) {
            assignmentService.deactivateAssignment(row.getId());
        }
        log.info("[PROVIDER-ADMIN] Staff {} deactivated at facility {} by user {}",
            memberId, seat.facility().getId(), seat.userId());
        return Optional.of(memberAfterChange(seat, memberId));
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
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        int reinvited = 0;
        for (UserRoleHospitalAssignment row : rows.get()) {
            if (!Boolean.TRUE.equals(row.getActive())) {
                assignmentService.regenerateAssignmentCode(row.getId(), true);
                reinvited++;
            }
        }
        log.info("[PROVIDER-ADMIN] Staff {} re-invited at facility {} ({} assignment(s)) by user {}",
            memberId, seat.facility().getId(), reinvited, seat.userId());
        return Optional.of(memberAfterChange(seat, memberId));
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
     * ADMIN, SUPER_ADMIN) anywhere, active or not. Admins are administered by
     * the platform, never by a peer, as {@code UserAccountAccess} decides for
     * a hospital admin.
     */
    private Optional<List<UserRoleHospitalAssignment>> manageableRows(ProviderSeat seat, UUID userId) {
        if (userId == null || userId.equals(seat.userId())) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> everyRow = assignmentRepository.findByUserId(userId);
        if (everyRow.stream().anyMatch(row -> UserAccountAccess.ADMIN_ROLES.contains(roleOf(row)))) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> here = everyRow.stream()
            .filter(row -> row.getHospital() != null && seat.facility().getId().equals(row.getHospital().getId()))
            .filter(row -> !PATIENT.equals(roleOf(row)))
            .toList();
        if (here.isEmpty()) {
            return Optional.empty();
        }
        User member = here.get(0).getUser();
        if (member == null || member.isDeleted()) {
            return Optional.empty();
        }
        return Optional.of(here);
    }

    private ProviderStaffMemberDTO memberAfterChange(ProviderSeat seat, UUID userId) {
        List<UserRoleHospitalAssignment> rows = staffRowsByUser(seat.facility().getId()).get(userId);
        return toMember(rows == null ? List.of() : rows, seat);
    }

    /** Staff rows at the facility (no PATIENT row, no deleted account), grouped by holder in a stable order. */
    private Map<UUID, List<UserRoleHospitalAssignment>> staffRowsByUser(UUID facilityId) {
        Map<UUID, List<UserRoleHospitalAssignment>> byUser = new LinkedHashMap<>();
        for (UserRoleHospitalAssignment row : assignmentRepository.findStaffRowsByHospitalId(facilityId)) {
            User holder = row.getUser();
            if (holder == null || holder.getId() == null || holder.isDeleted() || PATIENT.equals(roleOf(row))) {
                continue;
            }
            byUser.computeIfAbsent(holder.getId(), id -> new ArrayList<>()).add(row);
        }
        return byUser;
    }

    private static ProviderStaffMemberDTO toMember(List<UserRoleHospitalAssignment> rows, ProviderSeat seat) {
        User holder = rows.isEmpty() ? null : rows.get(0).getUser();
        TreeSet<String> roles = new TreeSet<>();
        boolean active = false;
        boolean pending = false;
        for (UserRoleHospitalAssignment row : rows) {
            String role = roleOf(row);
            if (!role.isEmpty()) {
                roles.add(role);
            }
            boolean rowActive = Boolean.TRUE.equals(row.getActive());
            active |= rowActive;
            pending |= !rowActive && row.getConfirmationCode() != null && row.getConfirmationVerifiedAt() == null;
        }
        if (holder == null) {
            return ProviderStaffMemberDTO.builder().roles(List.of()).build();
        }
        return ProviderStaffMemberDTO.builder()
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
            .build();
    }

    private ProviderProfileDTO toProfile(ProviderSeat seat) {
        Hospital facility = seat.facility();
        ProviderProfileDTO.ProviderProfileDTOBuilder dto = ProviderProfileDTO.builder()
            .id(facility.getId())
            .facilityType(FacilityType.orHospital(facility.getFacilityType()))
            .code(facility.getCode())
            .name(facility.getName())
            .phoneNumber(facility.getPhoneNumber())
            .email(facility.getEmail())
            .website(facility.getWebsite())
            .address(facility.getAddress())
            .city(facility.getCity())
            .region(facility.getRegion())
            .editable(seat.admin());
        verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(facility.getId())
            .ifPresent(v -> applyVerification(dto, v));
        return dto.build();
    }

    private static void applyVerification(ProviderProfileDTO.ProviderProfileDTOBuilder dto, ProviderVerification v) {
        dto.legalName(v.getLegalName())
            .tradeName(v.getTradeName())
            .licenceNumber(v.getLicenceNumber())
            .licenceAuthority(v.getLicenceAuthority())
            .companyPhone(v.getCompanyPhone())
            .verificationStatus(v.getStatus())
            .verifiedAt(v.getStatus() == ProviderVerificationStatus.VERIFIED ? v.getDecidedAt() : null);
    }

    private static String roleOf(UserRoleHospitalAssignment row) {
        if (row.getRole() == null) {
            return "";
        }
        String code = row.getRole().getCode() != null ? row.getRole().getCode() : row.getRole().getName();
        return RoleFacilityCompatibility.bare(code);
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
