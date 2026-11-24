package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.NotificationPreference;
import com.example.hms.payload.dto.notification.PatientNotificationDeliveryDTO;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** QA r73 D3700: the recorder of a consent choice, as each reader may see it. */
class NotificationPreferenceViewsTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ConsentRecorderLabels labels = new ConsentRecorderLabels(users);
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID receptionistA = UUID.randomUUID();
    private final UUID patientAccount = UUID.randomUUID();

    @Test
    void staffNamesAreBatchedScopedToTheReadersHospitalAndNeverFallBackToContactIdentifiers() {
        var name = actor(receptionistA, " cdx ", "Recorder");
        when(users.findStaffActorNamesAtHospital(List.of(receptionistA), hospitalA)).thenReturn(List.of(name));
        var preference = refusalBy(receptionistA);

        var dto = NotificationPreferenceViews.of(preference, patientAccount,
            labels.forStaffViewer(NotificationPreferenceViews.recorders(List.of(preference)), hospitalA));

        assertThat(dto.getRecordedByName()).isEqualTo("cdx Recorder");
        assertThat(dto.getRecordedByKind()).isEqualTo("STAFF");
        assertThat(dto.getRecordedAt()).isEqualTo(preference.getRecordedAt());
        verify(users).findStaffActorNamesAtHospital(List.of(receptionistA), hospitalA);
        verify(users, never()).findClinicalActorNames(any());
        verify(name, never()).getUsername();
    }

    @Test
    void hospitalBsAdminSeesAnotherFacility_withNoNameAndNoId() {
        when(users.findStaffActorNamesAtHospital(List.of(receptionistA), hospitalB)).thenReturn(List.of());
        var preference = refusalBy(receptionistA);

        var dto = NotificationPreferenceViews.of(preference, patientAccount,
            labels.forStaffViewer(List.of(receptionistA), hospitalB));
        var delivery = new PatientNotificationDeliveryDTO();
        labels.forStaffViewer(List.of(receptionistA), hospitalB).apply(delivery, receptionistA, patientAccount);

        assertThat(dto.getRecordedByKind()).isEqualTo("OTHER_FACILITY");
        assertThat(dto.getRecordedByName()).isNull();
        assertThat(delivery.getConsentRecordedByKind()).isEqualTo("OTHER_FACILITY");
        assertThat(delivery.getConsentRecordedByName()).isNull();
        verify(users, never()).findClinicalActorNames(any());
    }

    @Test
    void thePatientSeesTheStaffNameOfWhoeverRecordedTheirChoice_andTheirOwnChoiceAsTheirs() {
        var name = actor(receptionistA, "Test", "Accueil");
        when(users.findClinicalActorNames(List.of(receptionistA))).thenReturn(List.of(name));
        var byDesk = refusalBy(receptionistA);
        var bySelf = refusalBy(patientAccount);
        var owner = labels.forAccountOwner(NotificationPreferenceViews.recorders(List.of(byDesk, bySelf)), patientAccount);

        var desk = NotificationPreferenceViews.of(byDesk, patientAccount, owner);
        var self = NotificationPreferenceViews.of(bySelf, patientAccount, owner);

        assertThat(desk.getRecordedByKind()).isEqualTo("STAFF");
        assertThat(desk.getRecordedByName()).isEqualTo("Test Accueil");
        assertThat(self.getRecordedByKind()).isEqualTo("PATIENT");
        assertThat(self.getRecordedByName()).isNull();
        // The owner's own id is never looked up, only the staff recorder's.
        verify(users).findClinicalActorNames(List.of(receptionistA));
    }

    @Test
    void legacyChoicesRemainHonestlyUnattributedWithoutAUserLookup() {
        var legacy = NotificationPreference.builder().notificationType(NotificationType.BILLING)
            .channel(NotificationChannel.SMS).enabled(false).build();
        var dto = NotificationPreferenceViews.of(legacy, patientAccount,
            labels.forStaffViewer(NotificationPreferenceViews.recorders(List.of(legacy)), hospitalA));
        assertThat(dto.getRecordedAt()).isNull();
        assertThat(dto.getRecordedByKind()).isNull();
        assertThat(dto.getRecordedByName()).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void theAuditWordingCarriesTheOldAndNewStateOnly() {
        assertThat(NotificationPreferenceViews.change(NotificationType.LAB_RESULT, NotificationChannel.EMAIL, true, false))
            .isEqualTo("LAB_RESULT/EMAIL on->off");
    }

    private NotificationPreference refusalBy(UUID recorder) {
        var preference = NotificationPreference.builder().notificationType(NotificationType.APPOINTMENT_REMINDER)
            .channel(NotificationChannel.SMS).build();
        preference.recordChoice(false, recorder, LocalDateTime.of(2026, 11, 24, 1, 29));
        return preference;
    }

    private static UserRepository.ClinicalActorName actor(UUID id, String first, String last) {
        var name = mock(UserRepository.ClinicalActorName.class);
        when(name.getId()).thenReturn(id);
        when(name.getFirstName()).thenReturn(first);
        when(name.getLastName()).thenReturn(last);
        return name;
    }
}
