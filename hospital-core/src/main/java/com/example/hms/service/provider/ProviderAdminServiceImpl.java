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
import java.util.HashMap;
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
        // QA D833: the facility number is stored like the super-admin form stores it.
        if (body.getPhoneNumber() != null && !body.getPhoneNumber().equals(facility.getPhoneNumber())) {
            String phone = com.example.hms.utility.PhoneNumbers.normalizeFacilityPhone(
                body.getPhoneNumber(), facility.getCountry());
            if (phone == null) {
                throw new com.example.hms.exception.FieldBusinessException(
                    "phoneNumber", "hospital.phoneNumber.invalid");
            }
            body.setPhoneNumber(phone);
        }
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
        return seatResolver.currentAdmin().map(this::staffOf);
    }

    /** The facility's staff, each with what this admin may do to them. */
    private List<ProviderStaffMemberDTO> staffOf(ProviderSeat seat) {
        Map<UUID, List<UserRoleHospitalAssignment>> rowsHere = staffRowsByUser(seat.facility().getId());
        // Every member's rows everywhere, in ONE query: the admin-role rule
        // behind canDeactivate / canReinvite needs them, never one read per member.
        Map<UUID, List<UserRoleHospitalAssignment>> everyRowByUser = new HashMap<>();
        if (!rowsHere.isEmpty()) {
            for (UserRoleHospitalAssignment row : assignmentRepository.findByUserIdIn(rowsHere.keySet())) {
                if (row.getUser() != null && row.getUser().getId() != null) {
                    everyRowByUser.computeIfAbsent(row.getUser().getId(), id -> new ArrayList<>()).add(row);
                }
            }
        }
        return rowsHere.entrySet().stream()
            .map(entry -> toMember(entry.getValue(), seat, everyRowByUser.getOrDefault(entry.getKey(), List.of())))
            .flatMap(Optional::stream)
            .sorted(Comparator
                .comparing((ProviderStaffMemberDTO m) -> lower(m.getLastName()))
                .thenComparing(m -> lower(m.getFirstName()))
                .thenComparing(m -> lower(m.getUsername())))
            .toList();
    }

    @Override
    public Optional<ProviderStaffMemberDTO> deactivateStaff(String userId) {
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderSeat seat = found.get();
        UUID memberId = parseId(userId);
        Optional<MemberRows> rows = manageableRows(seat, memberId);
        // canDeactivate's rule: nothing active or invited here is nothing to
        // retire, answered like any refusal (no 200, no audit row for a no-op).
        if (rows.isEmpty() || !hasSomethingToRetire(rows.get().here())) {
            return Optional.empty();
        }
        // One scope read for the member's rows, all checked before any changes.
        assignmentService.deactivateProviderStaffAssignments(idsOf(rows.get().here()));
        log.info("[PROVIDER-ADMIN] Staff {} deactivated at facility {} by user {}",
            memberId, seat.facility().getId(), seat.userId());
        // The rows just changed (the same managed instances, read once in
        // manageableRows), never a second read.
        return toMember(rows.get().here(), seat, rows.get().everyRow());
    }

    @Override
    public Optional<ProviderStaffMemberDTO> activateStaff(String userId) {
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderSeat seat = found.get();
        UUID memberId = parseId(userId);
        Optional<MemberRows> rows = manageableRows(seat, memberId);
        // A disabled account stays disabled: entering the new code would
        // switch it back on (and clear its lockout), and only a super-admin
        // re-enables an account. An account that was never activated is
        // another matter: every admin-registered account starts inactive
        // (Option A) and the code IS its activation, so it may be re-invited.
        // canReinvite's rule: an account that may be re-invited AND a row that
        // is not active. All active is a no-op: refused (no 200, no audit row).
        if (rows.isEmpty() || !mayReinvite(rows.get().here().get(0).getUser())
                || rows.get().here().stream().noneMatch(row -> Boolean.FALSE.equals(row.getActive()))) {
            return Optional.empty();
        }
        List<UUID> inactive = rows.get().here().stream()
            .filter(row -> !Boolean.TRUE.equals(row.getActive()))
            .map(UserRoleHospitalAssignment::getId)
            .toList();
        if (!inactive.isEmpty()) {
            assignmentService.regenerateProviderStaffAssignmentCodes(inactive, true);
        }
        log.info("[PROVIDER-ADMIN] Staff {} re-invited at facility {} ({} assignment(s)) by user {}",
            memberId, seat.facility().getId(), inactive.size(), seat.userId());
        // The rows read once in manageableRows, never a second read.
        return toMember(rows.get().here(), seat, rows.get().everyRow());
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
    private Optional<MemberRows> manageableRows(ProviderSeat seat, UUID userId) {
        if (userId == null || userId.equals(seat.userId())) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> everyRow = assignmentRepository.findByUserId(userId);
        List<UserRoleHospitalAssignment> here = everyRow.stream()
            .filter(row -> row.getHospital() != null && seat.facility().getId().equals(row.getHospital().getId()))
            .filter(row -> !PATIENT.equals(UserAccountAccess.roleCode(row.getRole())))
            .toList();
        if (here.isEmpty() || !manageable(seat, here.get(0).getUser(), everyRow)) {
            return Optional.empty();
        }
        return Optional.of(new MemberRows(here, everyRow));
    }

    /**
     * A member's staff rows at the caller's facility, and every row they hold
     * anywhere (the admin-role rule reads those), from the one read.
     */
    private record MemberRows(List<UserRoleHospitalAssignment> here, List<UserRoleHospitalAssignment> everyRow) {
    }

    /**
     * May this account receive a new invitation code? An enabled account, or
     * one that was NEVER activated: under Option A every admin-registered
     * account starts inactive and entering the code is what activates it.
     *
     * <p>"Never activated" means it never signed in, on either path: no
     * {@code lastLoginAt} (the legacy login and the MFA verify stamp it) and
     * no {@code lastOidcLoginAt} (the Keycloak session bootstrap stamps it).
     * Those are the only sign-in stamps an account carries. The assignments'
     * {@code confirmationVerifiedAt} is NOT used: re-issuing or restarting an
     * invitation clears it, so it cannot tell "never used" from "disabled
     * after use". An account that signed in once and is inactive now was
     * disabled, and only a super-admin re-enables it: a peer admin's re-invite
     * would undo that.
     *
     * <p>And never an account an administrator switched off
     * ({@code deactivatedByAdminAt}, V184), signed in or not: a disable before
     * the first sign-in would otherwise read as a pending invitation.
     */
    static boolean mayReinvite(User member) {
        if (member == null || member.getDeactivatedByAdminAt() != null) {
            return false;
        }
        if (member.isActive()) {
            return true;
        }
        return member.getLastLoginAt() == null && member.getLastOidcLoginAt() == null;
    }

    /**
     * A row deactivate would change: retire()'s own rule (active, any code
     * still on it, or a temporary password). A row left inactive with a live
     * code can still switch the account on, so it is something to retire.
     */
    private static boolean hasSomethingToRetire(List<UserRoleHospitalAssignment> rows) {
        return rows.stream().anyMatch(row -> !Boolean.FALSE.equals(row.getActive())
            || row.getConfirmationCode() != null || row.getTempPlainPassword() != null);
    }

    private static boolean isPendingInvitation(UserRoleHospitalAssignment row) {
        return !Boolean.TRUE.equals(row.getActive())
            && row.getConfirmationCode() != null && row.getConfirmationVerifiedAt() == null;
    }

    /**
     * The one rule of who an admin may change here (deactivate, re-invite):
     * not the caller, not a deleted account, and nobody holding an admin role
     * anywhere, active or not, by assignment or by global role. The staff
     * list's {@code canDeactivate} / {@code canReinvite} come from this same
     * method, so the portal never offers what the server refuses.
     */
    private static boolean manageable(ProviderSeat seat, User member, List<UserRoleHospitalAssignment> everyRow) {
        return member != null
            && !member.isDeleted()
            && !Objects.equals(member.getId(), seat.userId())
            && !UserAccountAccess.holdsAdminRole(member, everyRow);
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
    private static Optional<ProviderStaffMemberDTO> toMember(List<UserRoleHospitalAssignment> rows, ProviderSeat seat,
                                                             List<UserRoleHospitalAssignment> everyRow) {
        User holder = rows.isEmpty() ? null : rows.get(0).getUser();
        if (holder == null) {
            return Optional.empty();
        }
        boolean manageable = manageable(seat, holder, everyRow);
        TreeSet<String> roles = new TreeSet<>();
        boolean active = false;
        boolean pending = false;
        boolean anyInactive = false;
        for (UserRoleHospitalAssignment row : rows) {
            String role = UserAccountAccess.roleCode(row.getRole());
            if (!role.isEmpty()) {
                roles.add(role);
            }
            boolean rowActive = Boolean.TRUE.equals(row.getActive());
            active |= rowActive;
            anyInactive |= Boolean.FALSE.equals(row.getActive());
            pending |= isPendingInvitation(row);
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
            // The server's own rules (manageable, and activateStaff's): a
            // deactivate retires what is active or invited; a re-invite needs
            // an account that may be re-invited and a row that is not active.
            .canDeactivate(manageable && hasSomethingToRetire(rows))
            .canReinvite(manageable && mayReinvite(holder) && anyInactive)
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
        verificationRepository.findFirstByHospital_IdOrderByCreatedAtDescIdDesc(facility.getId())
            .ifPresent(latest -> dto.verificationStatus(latest.getStatus()));
        verificationRepository.findFirstByHospital_IdAndStatusOrderByCreatedAtDescIdDesc(facility.getId(),
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
