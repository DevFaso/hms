package com.example.hms.service.credentialing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hms.enums.LicenseAlertStage;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The nightly licence sweep (Tier 2 item 40).
 *
 * <p>The behaviour worth pinning is not that it notifies — it is that it
 * <em>stops</em> notifying. A sweep that re-sent the same warning every
 * morning would be worse than no sweep at all: the administrator filters the
 * category within a week and the one that mattered goes with it.
 */
@ExtendWith(MockitoExtension.class)
class LicenseExpirySweepServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    @Mock private StaffRepository staffRepository;
    @Mock private NotificationService notificationService;

    private LicenseExpirySweepService service;

    private UUID hospitalId;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new LicenseExpirySweepService(staffRepository, notificationService, clock);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
    }

    private Staff staff(String username, LocalDate expiry, LicenseAlertStage alreadyNotified) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);

        Staff s = new Staff();
        s.setId(UUID.randomUUID());
        s.setHospital(hospital);
        s.setUser(user);
        s.setLicenseExpiryDate(expiry);
        s.setLicenseAlertStage(alreadyNotified);
        return s;
    }

    private void adminsAre(String... usernames) {
        when(staffRepository.findActiveUsernamesByHospitalAndRole(
            eq(hospitalId), eq(LicenseExpirySweepService.ADMIN_ROLE)))
            .thenReturn(List.of(usernames));
    }

    @Test
    void aFirstWarningNotifiesThePractitionerAndTheAdministrators() {
        Staff s = staff("dr.traore", TODAY.plusDays(60), null);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));
        adminsAre("hadmin1", "hadmin2");

        assertThat(service.sweep()).isEqualTo(1);

        // The practitioner is the only person who can actually obtain the
        // renewal; telling only the enforcer makes this an argument instead.
        verify(notificationService).createNotification(
            anyString(), eq("dr.traore"), eq(LicenseExpirySweepService.NOTIFICATION_TYPE));
        verify(notificationService).createNotification(
            anyString(), eq("hadmin1"), eq(LicenseExpirySweepService.NOTIFICATION_TYPE));
        verify(notificationService).createNotification(
            anyString(), eq("hadmin2"), eq(LicenseExpirySweepService.NOTIFICATION_TYPE));

        assertThat(s.getLicenseAlertStage()).isEqualTo(LicenseAlertStage.WARNING);
    }

    @Test
    void theSameStageIsNotNotifiedTwice() {
        // Run the sweep again the next night against a licence that has not
        // changed category. This is the guard the whole design turns on.
        Staff s = staff("dr.traore", TODAY.plusDays(60), LicenseAlertStage.WARNING);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));

        assertThat(service.sweep()).isZero();

        verifyNoInteractions(notificationService);
        verify(staffRepository, never()).save(any());
    }

    @Test
    void anAdvanceFromWarningToCriticalNotifiesAgain() {
        Staff s = staff("dr.traore", TODAY.plusDays(10), LicenseAlertStage.WARNING);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));
        adminsAre("hadmin1");

        assertThat(service.sweep()).isEqualTo(1);

        assertThat(s.getLicenseAlertStage()).isEqualTo(LicenseAlertStage.CRITICAL);
        verify(notificationService, times(2)).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void anExpiredLicenceSaysSoRatherThanCountingDownFromZero() {
        Staff s = staff("dr.traore", TODAY.minusDays(3), LicenseAlertStage.CRITICAL);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));
        adminsAre("hadmin1");

        service.sweep();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(2))
            .createNotification(message.capture(), anyString(), anyString());
        assertThat(message.getValue()).contains("expired on");
        assertThat(message.getValue()).doesNotContain("-3");
    }

    @Test
    void aPractitionerWhoIsAlsoTheAdministratorIsNotNotifiedTwice() {
        Staff s = staff("hadmin1", TODAY.plusDays(60), null);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));
        adminsAre("hadmin1");

        service.sweep();

        verify(notificationService, times(1)).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void aStaffMemberWithNobodyToNotifyIsStampedAnywayRatherThanRetriedForever() {
        // Otherwise the sweep re-attempts this row every night for the rest of
        // its life — the exact churn the stage guard exists to prevent.
        Staff s = staff(null, TODAY.plusDays(60), null);
        s.getUser().setUsername(null);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));
        adminsAre();

        assertThat(service.sweep()).isZero();

        assertThat(s.getLicenseAlertStage()).isEqualTo(LicenseAlertStage.WARNING);
        verify(staffRepository).save(s);
        verifyNoInteractions(notificationService);
    }

    @Test
    void aRowOutsideTheHorizonIsIgnoredRatherThanGraded() {
        // The query should not return it, but a service that trusted the query
        // absolutely would NPE the day the query changed.
        Staff s = staff("dr.traore", TODAY.plusYears(2), null);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));

        assertThat(service.sweep()).isZero();

        assertThat(s.getLicenseAlertStage()).isNull();
        verifyNoInteractions(notificationService);
    }

    @Test
    void anEmptySweepIsAQuietNoOp() {
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of());

        assertThat(service.sweep()).isZero();

        verifyNoInteractions(notificationService);
    }

    @Test
    void theHorizonAsksForExactlyTheWarningWindow() {
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of());

        service.sweep();

        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(staffRepository).findAllWithLicenseExpiringBefore(cutoff.capture());
        assertThat(cutoff.getValue())
            .isEqualTo(TODAY.plusDays(LicenseAlertStage.WARNING_DAYS));
    }

    @Test
    void aStaffMemberWithNoHospitalStillReachesThePractitioner() {
        Staff s = staff("dr.traore", TODAY.plusDays(60), null);
        s.setHospital(null);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));

        assertThat(service.sweep()).isEqualTo(1);

        verify(notificationService, times(1)).createNotification(
            anyString(), eq("dr.traore"), anyString());
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), anyString());
    }

    @Test
    void theMessageNamesTheLicenceHolderAndTheDate() {
        Staff s = staff("dr.traore", TODAY.plusDays(45), null);
        when(staffRepository.findAllWithLicenseExpiringBefore(any())).thenReturn(List.of(s));
        adminsAre("hadmin1");

        service.sweep();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(2))
            .createNotification(message.capture(), anyString(), anyString());
        // An administrator with forty staff needs to know WHICH licence
        // without opening anything.
        assertThat(message.getValue()).contains("dr.traore");
        assertThat(message.getValue()).contains(TODAY.plusDays(45).toString());
        assertThat(message.getValue()).contains("45");
    }
}
