package com.example.hms.service.credentialing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.LicenseAlertStage;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffCredentialRenewal;
import com.example.hms.model.User;
import com.example.hms.repository.StaffCredentialRenewalRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * The credential-renewal ceremony (Tier 2 item 40).
 *
 * <p>Fixed clock, for the same reason the pharmacist ceremony uses one: a
 * ceremony that stamps the wall clock is a test that cannot assert what it
 * stamped.
 */
@ExtendWith(MockitoExtension.class)
class CredentialRenewalServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 9, 0);
    private static final LocalDate OLD_EXPIRY = LocalDate.of(2026, 9, 30);
    private static final LocalDate NEW_EXPIRY = LocalDate.of(2027, 9, 30);

    @Mock private StaffRepository staffRepository;
    @Mock private StaffCredentialRenewalRepository renewalRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;

    private CredentialRenewalService service;

    private UUID hospitalId;
    private UUID adminUserId;
    private UUID practitionerUserId;
    private Hospital hospital;
    private Staff staff;
    private User admin;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new CredentialRenewalService(
            staffRepository, renewalRepository, userRepository, roleValidator, clock);

        hospitalId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
        practitionerUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        User practitionerUser = new User();
        practitionerUser.setId(practitionerUserId);

        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);
        staff.setUser(practitionerUser);
        staff.setLicenseNumber("MED-1234");
        staff.setLicenseExpiryDate(OLD_EXPIRY);
        // The state the sweep left behind: this practitioner has already been
        // warned once.
        staff.setLicenseAlertStage(LicenseAlertStage.CRITICAL);

        admin = new User();
        admin.setId(adminUserId);
        admin.setUsername("hadmin1");
    }

    private void callerIsAdmin() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(adminUserId);
        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
    }

    private StaffCredentialRenewal captureSavedRenewal() {
        ArgumentCaptor<StaffCredentialRenewal> captor =
            ArgumentCaptor.forClass(StaffCredentialRenewal.class);
        verify(renewalRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordingARenewalMovesTheLicenceAndStampsServerIdentityAndClock() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), NEW_EXPIRY, null, "Ordre des medecins", "Seen original.");

        assertThat(staff.getLicenseExpiryDate()).isEqualTo(NEW_EXPIRY);
        assertThat(staff.getLicenseIssuingAuthority()).isEqualTo("Ordre des medecins");
        assertThat(staff.getLicenseVerifiedAt()).isEqualTo(NOW);
        assertThat(staff.getLicenseVerifiedBy()).isSameAs(admin);
        verify(staffRepository).save(staff);
    }

    @Test
    void recordsAQualificationThatDoesNotExpire() {
        // How clinicians are actually credentialed in this deployment: on a
        // diploma, which has no expiry. Until V145 expiryDate was mandatory,
        // so filing one meant inventing a date that does not exist -- the
        // screen could not record the thing it is for.
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), null, "DIP-2019-4471",
            "Universite Joseph Ki-Zerbo", "Diploma seen, original sighted.");

        // Null is a positive statement -- "does not expire" -- so it clears
        // any date on file rather than leaving a stale one behind.
        assertThat(staff.getLicenseExpiryDate()).isNull();
        assertThat(staff.getLicenseNumber()).isEqualTo("DIP-2019-4471");
        assertThat(staff.getLicenseVerifiedAt()).isEqualTo(NOW);
        assertThat(staff.getLicenseVerifiedBy()).isSameAs(admin);

        // And the history still records who attested to it and when, which is
        // the half of V140 that matters just as much for a diploma.
        StaffCredentialRenewal saved = captureSavedRenewal();
        assertThat(saved.getExpiryDate()).isNull();
        assertThat(saved.getLicenseNumber()).isEqualTo("DIP-2019-4471");
        assertThat(saved.getRecordedBy()).isSameAs(admin);
    }

    @Test
    void aNonExpiringCredentialLeavesNothingForTheExpirySweepToFind() {
        // The sweep selects on "licenseExpiryDate IS NOT NULL", so clearing
        // the date is what takes this person out of the alert ladder. If a
        // null were ever stored as a far-future date instead, they would sit
        // in the query forever waiting to become urgent.
        staff.setLicenseExpiryDate(NEW_EXPIRY);
        staff.setLicenseAlertStage(LicenseAlertStage.WARNING);
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), null, null, null, null);

        assertThat(staff.getLicenseExpiryDate()).isNull();
        assertThat(staff.getLicenseAlertStage()).isNull();
    }

    @Test
    void thePreviousValuesAreCapturedBeforeTheStaffRowIsMutated() {
        // The whole reason the history table exists. Reading them back after
        // the update would record the new values twice and the history would
        // assert nothing ever changed.
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), NEW_EXPIRY, "MED-9999", null, null);

        StaffCredentialRenewal saved = captureSavedRenewal();
        assertThat(saved.getPreviousExpiryDate()).isEqualTo(OLD_EXPIRY);
        assertThat(saved.getPreviousLicenseNumber()).isEqualTo("MED-1234");
        assertThat(saved.getExpiryDate()).isEqualTo(NEW_EXPIRY);
        assertThat(saved.getLicenseNumber()).isEqualTo("MED-9999");
        assertThat(saved.getRecordedBy()).isSameAs(admin);
        assertThat(saved.getRecordedAt()).isEqualTo(NOW);
        assertThat(saved.getHospital()).isSameAs(hospital);
    }

    @Test
    void theAlertStageIsClearedSoTheLicenceCanAlertAgainLater() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), NEW_EXPIRY, null, null, null);

        // Left at CRITICAL, the sweep would stay silent right through the new
        // licence's own expiry — the guard would have become a gag.
        assertThat(staff.getLicenseAlertStage()).isNull();
    }

    @Test
    void aNullLicenceNumberKeepsWhatIsAlreadyOnFile() {
        // Most renewals reissue the same number; making an administrator
        // retype it is how a digit gets lost.
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), NEW_EXPIRY, "   ", "  ", null);

        assertThat(staff.getLicenseNumber()).isEqualTo("MED-1234");
        assertThat(staff.getLicenseIssuingAuthority()).isNull();
    }

    @Test
    void aBlankNoteIsStoredAsNullRatherThanWhitespace() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), NEW_EXPIRY, null, null, "   ");

        assertThat(captureSavedRenewal().getNote()).isNull();
    }

    @Test
    void aPractitionerCannotRecordTheirOwnRenewal() {
        // Credentialing is somebody attesting they saw the document. A
        // practitioner attesting to their own is the same non-check the
        // co-sign and pharmacist ceremonies already refuse.
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(practitionerUserId);
        User self = new User();
        self.setId(practitionerUserId);
        when(userRepository.findById(practitionerUserId)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> service.recordRenewal(staff.getId(), NEW_EXPIRY, null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot record their own");

        verify(renewalRepository, never()).save(any());
        assertThat(staff.getLicenseExpiryDate()).isEqualTo(OLD_EXPIRY);
    }

    @Test
    void aShorterExpiryIsAllowedAsACorrectionAndLandsInTheHistory() {
        // An administrator correcting a date entered wrong must be able to.
        // The history is what makes the shortening visible rather than silent.
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDate earlier = OLD_EXPIRY.minusMonths(6);
        service.recordRenewal(staff.getId(), earlier, null, null, "Date entered wrong in June.");

        assertThat(staff.getLicenseExpiryDate()).isEqualTo(earlier);
        StaffCredentialRenewal saved = captureSavedRenewal();
        assertThat(saved.getPreviousExpiryDate()).isEqualTo(OLD_EXPIRY);
        assertThat(saved.getExpiryDate()).isEqualTo(earlier);
    }

    @Test
    void anotherHospitalsStaffReadsAsNotFound() {
        // 404-not-403: another hospital's staff member is indistinguishable
        // from one that does not exist.
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.recordRenewal(staff.getId(), NEW_EXPIRY, null, null, null))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(renewalRepository, never()).save(any());
    }

    @Test
    void anUnknownStaffMemberReadsAsNotFound() {
        UUID missing = UUID.randomUUID();
        when(staffRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordRenewal(missing, NEW_EXPIRY, null, null, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnresolvableCallerIsRefused() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(null);

        assertThatThrownBy(() -> service.recordRenewal(staff.getId(), NEW_EXPIRY, null, null, null))
            .isInstanceOf(AccessDeniedException.class);

        verify(renewalRepository, never()).save(any());
    }

    @Test
    void aCallerWhoseUserRowIsGoneIsRefused() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(adminUserId);
        when(userRepository.findById(adminUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordRenewal(staff.getId(), NEW_EXPIRY, null, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aFirstRecordingHasNoPreviousExpiry() {
        // Distinguishable from a renewal in the UI, which is why the column
        // is nullable rather than defaulted.
        staff.setLicenseExpiryDate(null);
        staff.setLicenseNumber(null);
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        callerIsAdmin();
        when(renewalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRenewal(staff.getId(), NEW_EXPIRY, "MED-0001", null, null);

        StaffCredentialRenewal saved = captureSavedRenewal();
        assertThat(saved.getPreviousExpiryDate()).isNull();
        assertThat(saved.getPreviousLicenseNumber()).isNull();
    }

    @Test
    void historyIsScopedToTheCallersHospital() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.history(staff.getId()))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(renewalRepository, never()).findByStaffIdOrderByRecordedAtDesc(any());
    }

    @Test
    void historyReadsNewestFirstForTheRightStaffMember() {
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(renewalRepository.findByStaffIdOrderByRecordedAtDesc(staff.getId()))
            .thenReturn(java.util.List.of(new StaffCredentialRenewal()));

        assertThat(service.history(staff.getId())).hasSize(1);
        verify(renewalRepository).findByStaffIdOrderByRecordedAtDesc(staff.getId());
    }
}
