package com.example.hms.service.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.provider.ProviderProfileDTO;
import com.example.hms.payload.dto.provider.ProviderProfileUpdateDTO;
import com.example.hms.payload.dto.provider.ProviderStaffMemberDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.service.UserRoleHospitalAssignmentService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The provider admin pages (provider plan P1-T5, AC-6): whose rows a provider
 * admin may change, and that every change goes through the assignment
 * service's own guarded methods.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderAdminServiceImplTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @Mock private ProviderSeatResolver seatResolver;
    @Mock private ProviderOrganisationsFlag organisationsFlag;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private ProviderVerificationRepository verificationRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRoleHospitalAssignmentService assignmentService;

    private ProviderAdminServiceImpl service;

    private final Hospital pharmacy = facility(FacilityType.PHARMACY);
    private final Hospital otherPharmacy = facility(FacilityType.PHARMACY);
    private final Hospital hospital = facility(FacilityType.HOSPITAL);
    private final User adminUser = user("admin");
    private final ProviderSeat adminSeat = new ProviderSeat(pharmacy, adminUser.getId(), true);
    private final ProviderSeat staffSeat = new ProviderSeat(pharmacy, adminUser.getId(), false);

    @BeforeAll
    static void validatorUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void validatorDown() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        service = new ProviderAdminServiceImpl(seatResolver, organisationsFlag, hospitalRepository,
            verificationRepository, assignmentRepository, assignmentService, validator);
    }

    // ── no seat ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no seat: every page answers empty and nothing is read or written")
    void noSeat() {
        when(seatResolver.current()).thenReturn(Optional.empty());
        when(seatResolver.currentAdmin()).thenReturn(Optional.empty());

        assertThat(service.getProfile()).isEmpty();
        assertThat(service.getSettings()).isEmpty();
        assertThat(service.listStaff()).isEmpty();
        assertThat(service.updateProfile(new ProviderProfileUpdateDTO())).isEmpty();
        assertThat(service.deactivateStaff(UUID.randomUUID().toString())).isEmpty();
        assertThat(service.activateStaff(UUID.randomUUID().toString())).isEmpty();
        verifyNoInteractions(assignmentService, hospitalRepository, assignmentRepository);
    }

    @Test
    @DisplayName("a staff seat that is not the admin: the admin pages answer empty")
    void notTheAdmin() {
        when(seatResolver.current()).thenReturn(Optional.of(staffSeat));
        when(seatResolver.currentAdmin()).thenReturn(Optional.empty());

        assertThat(service.getProfile()).isPresent();
        assertThat(service.listStaff()).isEmpty();
        assertThat(service.updateProfile(ProviderProfileUpdateDTO.builder().phoneNumber("+22670000000").build()))
            .isEmpty();
        verifyNoInteractions(assignmentService, hospitalRepository);
    }

    // ── profile ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the admin's update sets the operational contact only; blank optional fields clear")
    void updateProfileSetsContactOnly() {
        admin();
        pharmacy.setName("Pharmacie du Centre");
        pharmacy.setLicenseNumber("LIC-1");

        Optional<ProviderProfileDTO> profile = service.updateProfile(ProviderProfileUpdateDTO.builder()
            .phoneNumber(" +22670112233 ").email(" ").website("https://pharmacy.test").build());

        assertThat(profile).isPresent();
        assertThat(pharmacy.getPhoneNumber()).isEqualTo("+22670112233");
        assertThat(pharmacy.getEmail()).isNull();
        assertThat(pharmacy.getWebsite()).isEqualTo("https://pharmacy.test");
        assertThat(pharmacy.getName()).isEqualTo("Pharmacie du Centre");
        assertThat(pharmacy.getLicenseNumber()).isEqualTo("LIC-1");
        verify(hospitalRepository).save(pharmacy);
    }

    @Test
    @DisplayName("an invalid update from the admin is a constraint violation, and nothing is saved")
    void updateProfileValidatesAfterTheSeat() {
        admin();
        ProviderProfileUpdateDTO invalid = ProviderProfileUpdateDTO.builder().phoneNumber(" ").email("nope").build();

        assertThatThrownBy(() -> service.updateProfile(invalid)).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.updateProfile(null)).isInstanceOf(ConstraintViolationException.class);
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("the identity (name included) comes from the VERIFIED evidence; the status is the latest row's")
    void profileShowsVerifiedIdentity() {
        when(seatResolver.current()).thenReturn(Optional.of(staffSeat));
        pharmacy.setName("Name from the facility row");
        ProviderVerification verified = verification(ProviderVerificationStatus.VERIFIED, "LIC-9");
        verified.setTradeName("Pharmacie du Centre");
        verified.setAddressCity("Ouagadougou");
        verified.setDecidedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        latestIs(verified);
        verifiedIs(verified);

        ProviderProfileDTO profile = service.getProfile().orElseThrow();

        assertThat(profile.getName()).isEqualTo("Pharmacie du Centre");
        assertThat(profile.getLegalName()).isEqualTo("Pharmacie SARL");
        assertThat(profile.getLicenceNumber()).isEqualTo("LIC-9");
        assertThat(profile.getCity()).isEqualTo("Ouagadougou");
        assertThat(profile.getVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 0));
        assertThat(profile.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
        assertThat(profile.isEditable()).isFalse();
    }

    @Test
    @DisplayName("revoked, then new evidence submitted: no identity at all, never the submitted one; status SUBMITTED")
    void revokedAndResubmittedShowsNoIdentity() {
        when(seatResolver.current()).thenReturn(Optional.of(staffSeat));
        pharmacy.setName("Name from the submitted evidence");
        latestIs(verification(ProviderVerificationStatus.SUBMITTED, "LIC-NEW"));
        verifiedIs(null);

        ProviderProfileDTO profile = service.getProfile().orElseThrow();

        assertThat(profile.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
        assertThat(profile.getName()).isNull();
        assertThat(profile.getLegalName()).isNull();
        assertThat(profile.getLicenceNumber()).isNull();
        assertThat(profile.getCity()).isNull();
        assertThat(profile.getAddress()).isNull();
        assertThat(profile.getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("settings: the facility, the admin bit and the flag")
    void settings() {
        when(seatResolver.current()).thenReturn(Optional.of(adminSeat));
        when(organisationsFlag.isEnabled()).thenReturn(true);

        var settings = service.getSettings().orElseThrow();

        assertThat(settings.getFacilityId()).isEqualTo(pharmacy.getId());
        assertThat(settings.getFacilityType()).isEqualTo(FacilityType.PHARMACY);
        assertThat(settings.isProviderAdmin()).isTrue();
        assertThat(settings.isOrganisationsEnabled()).isTrue();
    }

    // ── staff ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the staff list is the facility's staff rows, grouped by person; no PATIENT row, no deleted account")
    void listStaff() {
        admin();
        User pharmacist = user("pharm");
        User deleted = user("gone");
        deleted.setDeleted(true);
        UserRoleHospitalAssignment pending = row(pharmacist, "ROLE_PHARMACIST", pharmacy, false);
        pending.setConfirmationCode("123456");
        when(assignmentRepository.findStaffRowsByHospitalId(pharmacy.getId())).thenReturn(List.of(
            row(adminUser, "ROLE_PROVIDER_ADMIN", pharmacy, true),
            pending,
            row(deleted, "ROLE_PHARMACIST", pharmacy, true),
            row(user("pat"), "ROLE_PATIENT", pharmacy, true)));

        List<ProviderStaffMemberDTO> staff = service.listStaff().orElseThrow();

        assertThat(staff).extracting(ProviderStaffMemberDTO::getUserId)
            .containsExactlyInAnyOrder(adminUser.getId(), pharmacist.getId());
        ProviderStaffMemberDTO admin = staff.stream().filter(m -> m.getUserId().equals(adminUser.getId()))
            .findFirst().orElseThrow();
        assertThat(admin.isProviderAdmin()).isTrue();
        assertThat(admin.isSelf()).isTrue();
        ProviderStaffMemberDTO member = staff.stream().filter(m -> m.getUserId().equals(pharmacist.getId()))
            .findFirst().orElseThrow();
        assertThat(member.isActive()).isFalse();
        assertThat(member.isInvitationPending()).isTrue();
        assertThat(member.getRoles()).containsExactly("PHARMACIST");
    }

    @Test
    @DisplayName("deactivate goes through the guarded service, for the member's rows HERE only")
    void deactivateOwnRowsOnly() {
        admin();
        User pharmacist = user("pharm");
        UserRoleHospitalAssignment here = row(pharmacist, "ROLE_PHARMACIST", pharmacy, true);
        UserRoleHospitalAssignment branch = row(pharmacist, "ROLE_PHARMACIST", otherPharmacy, true);
        UserRoleHospitalAssignment patient = row(pharmacist, "ROLE_PATIENT", hospital, true);
        member(pharmacist, here, branch, patient);

        Optional<ProviderStaffMemberDTO> answer = service.deactivateStaff(pharmacist.getId().toString());

        verify(assignmentService).deactivateAssignments(List.of(here.getId()));
        assertThat(answer).map(ProviderStaffMemberDTO::getUserId).contains(pharmacist.getId());
        // The answer is built from the rows just changed: no second read that could come back empty.
        verify(assignmentRepository, never()).findStaffRowsByHospitalId(any());
    }

    @Test
    @DisplayName("out of reach: elsewhere only, a peer admin, an admin anywhere, the caller, deleted, unknown, malformed")
    void outOfReach() {
        admin();
        User elsewhere = user("elsewhere");
        member(elsewhere, row(elsewhere, "ROLE_PHARMACIST", otherPharmacy, true));
        User peer = user("peer");
        member(peer, row(peer, "ROLE_PROVIDER_ADMIN", pharmacy, true));
        User formerAdmin = user("former");
        member(formerAdmin, row(formerAdmin, "ROLE_PHARMACIST", pharmacy, true),
            row(formerAdmin, "ROLE_HOSPITAL_ADMIN", hospital, false));
        User deleted = user("deleted");
        deleted.setDeleted(true);
        member(deleted, row(deleted, "ROLE_PHARMACIST", pharmacy, false));
        member(adminUser, row(adminUser, "ROLE_PHARMACIST", pharmacy, true));
        User globalAdmin = user("global-admin");
        globalAdmin.setUserRoles(Set.of(UserRole.builder().user(globalAdmin).role(role("ADMIN")).build()));
        member(globalAdmin, row(globalAdmin, "ROLE_PHARMACIST", pharmacy, true));
        User globalSuper = user("global-super");
        globalSuper.setUserRoles(Set.of(UserRole.builder().user(globalSuper).role(role("ROLE_SUPER_ADMIN")).build()));
        member(globalSuper, row(globalSuper, "ROLE_PHARMACIST", pharmacy, false));
        UUID unknown = UUID.randomUUID();
        when(assignmentRepository.findByUserId(unknown)).thenReturn(List.of());

        List<String> targets = new ArrayList<>(List.of(elsewhere.getId().toString(), peer.getId().toString(),
            formerAdmin.getId().toString(), deleted.getId().toString(), adminUser.getId().toString(),
            globalAdmin.getId().toString(), globalSuper.getId().toString(),
            unknown.toString(), "not-an-id", "", " "));
        targets.add(null);
        for (String target : targets) {
            assertThat(service.deactivateStaff(target)).as(String.valueOf(target)).isEmpty();
            assertThat(service.activateStaff(target)).as(String.valueOf(target)).isEmpty();
        }
        verifyNoInteractions(assignmentService);
    }

    @Test
    @DisplayName("activate re-invites the inactive rows here through a new code; an active row is left alone")
    void activateReinvitesInactiveRowsOnly() {
        admin();
        User member = user("lab");
        UserRoleHospitalAssignment inactive = row(member, "ROLE_PHARMACIST", pharmacy, false);
        UserRoleHospitalAssignment active = row(member, "ROLE_PHARMACIST", pharmacy, true);
        member(member, inactive, active);

        assertThat(service.activateStaff(member.getId().toString())).isPresent();

        verify(assignmentService).regenerateAssignmentCodes(List.of(inactive.getId()), true);
        verify(assignmentService, never()).deactivateAssignments(any());
    }

    @Test
    @DisplayName("activate never re-invites a disabled account: entering the code would switch it back on")
    void activateRefusesADisabledAccount() {
        admin();
        User disabled = user("disabled");
        disabled.setActive(false);
        member(disabled, row(disabled, "ROLE_PHARMACIST", pharmacy, false));

        assertThat(service.activateStaff(disabled.getId().toString())).isEmpty();
        verifyNoInteractions(assignmentService);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void admin() {
        when(seatResolver.current()).thenReturn(Optional.of(adminSeat));
        when(seatResolver.currentAdmin()).thenReturn(Optional.of(adminSeat));
    }

    private void member(User user, UserRoleHospitalAssignment... rows) {
        when(assignmentRepository.findByUserId(user.getId())).thenReturn(List.of(rows));
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setFacilityType(type);
        return h;
    }

    private void latestIs(ProviderVerification verification) {
        when(verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(pharmacy.getId()))
            .thenReturn(Optional.ofNullable(verification));
    }

    private void verifiedIs(ProviderVerification verification) {
        when(verificationRepository.findFirstByHospital_IdAndStatusOrderByCreatedAtDesc(pharmacy.getId(),
            ProviderVerificationStatus.VERIFIED)).thenReturn(Optional.ofNullable(verification));
    }

    private static ProviderVerification verification(ProviderVerificationStatus status, String licence) {
        ProviderVerification v = new ProviderVerification();
        v.setStatus(status);
        v.setLegalName("Pharmacie SARL");
        v.setLicenceNumber(licence);
        return v;
    }

    private static Role role(String code) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode(code);
        role.setName(code);
        return role;
    }

    private static User user(String name) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setActive(true);
        u.setUserRoles(new HashSet<>());
        u.setUsername(name);
        u.setFirstName(name);
        u.setLastName("Test");
        return u;
    }

    private static UserRoleHospitalAssignment row(User user, String roleCode, Hospital at, boolean active) {
        Role role = role(roleCode);
        UserRoleHospitalAssignment row = new UserRoleHospitalAssignment();
        row.setId(UUID.randomUUID());
        row.setUser(user);
        row.setRole(role);
        row.setHospital(at);
        row.setActive(active);
        return row;
    }
}
