package com.example.hms.service.notification;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D2203: the desk records a patient's SMS and e-mail choices, the rows the senders read. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientChannelPreferenceServiceTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private com.example.hms.repository.UserRepository userRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @InjectMocks private PatientChannelPreferenceService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final Patient patient = new Patient();
    private final User account = new User();
    private final List<NotificationPreference> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        patient.setId(UUID.randomUUID());
        account.setId(UUID.randomUUID());
        patient.setUser(account);
        when(userRepository.lockForNotificationPreferences(account.getId())).thenReturn(Optional.of(account));
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospitalId)).thenReturn(true);
        when(preferenceRepository.findByUser_Id(account.getId())).thenReturn(stored);
    }

    @Test
    void readsEveryDeskKindAndChannel_onUnlessTurnedOff() {
        stored.add(row(NotificationType.BILLING, NotificationChannel.EMAIL, false));
        stored.add(row(NotificationType.BILLING, NotificationChannel.IN_APP, false));

        List<NotificationPreferenceDTO> matrix = service.read(patient.getId(), hospitalId);

        assertThat(matrix).hasSize(PatientChannelPreferenceService.DESK_TYPES.size() * 2);
        assertThat(matrix).extracting(NotificationPreferenceDTO::getChannel)
            .containsOnly(NotificationChannel.SMS, NotificationChannel.EMAIL);
        assertThat(matrix).filteredOn(p -> !p.isEnabled()).singleElement()
            .satisfies(p -> {
                assertThat(p.getNotificationType()).isEqualTo(NotificationType.BILLING);
                assertThat(p.getChannel()).isEqualTo(NotificationChannel.EMAIL);
            });
    }

    @Test
    void recordsAChoice_keepsTheBell_andAuditsWhatChanged() {
        NotificationPreference bell = row(NotificationType.BILLING, NotificationChannel.IN_APP, false);
        stored.add(bell);

        List<NotificationPreferenceDTO> after = service.update(patient.getId(), hospitalId, List.of(
            update(NotificationType.APPOINTMENT_REMINDER, NotificationChannel.SMS, false),
            update(NotificationType.BILLING, NotificationChannel.EMAIL, true)), actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationPreference>> saved = ArgumentCaptor.forClass(List.class);
        verify(preferenceRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(NotificationPreference::getChannel)
            .doesNotContain(NotificationChannel.IN_APP);
        verify(preferenceRepository, never()).deleteByUser_Id(any());
        assertThat(bell.isEnabled()).isFalse();
        assertThat(after).filteredOn(p -> !p.isEnabled()).extracting(NotificationPreferenceDTO::getNotificationType)
            .containsExactly(NotificationType.APPOINTMENT_REMINDER);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PATIENT_CHANNEL_PREFERENCES_UPDATED);
        assertThat(audit.getValue().getUserId()).isEqualTo(actorId);
        assertThat(audit.getValue().getPatientId()).isEqualTo(patient.getId());
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Patient channel choices recorded at the desk: APPOINTMENT_REMINDER/SMS off");
    }

    @Test
    void nothingChanged_writesNoAuditRow() {
        service.update(patient.getId(), hospitalId,
            List.of(update(NotificationType.LAB_RESULT, NotificationChannel.SMS, true)), actorId);
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void aSwitchNoSenderReadsIsNeverWritten() {
        service.update(patient.getId(), hospitalId, List.of(
            update(NotificationType.GENERAL, NotificationChannel.SMS, false),
            update(NotificationType.BILLING, NotificationChannel.PUSH, false),
            update(NotificationType.BILLING, NotificationChannel.IN_APP, false)), actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationPreference>> saved = ArgumentCaptor.forClass(List.class);
        verify(preferenceRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).isEmpty();
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void aPatientNotRegisteredAtTheDesksHospitalIsNotFound() {
        UUID elsewhere = UUID.randomUUID();
        assertThatThrownBy(() -> service.read(patient.getId(), elsewhere))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(patient.getId(), null,
                List.of(update(NotificationType.BILLING, NotificationChannel.SMS, false)), actorId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(preferenceRepository, never()).saveAll(anyList());
    }

    @Test
    void aChangedChoiceCarriesTheAuthenticatedRecorderAndTime() {
        var before = java.time.LocalDateTime.now();
        var result = service.update(patient.getId(), hospitalId,
            List.of(update(NotificationType.LAB_RESULT, NotificationChannel.SMS, false)), actorId);
        assertThat(result).filteredOn(dto -> dto.getNotificationType() == NotificationType.LAB_RESULT
            && dto.getChannel() == NotificationChannel.SMS).singleElement().satisfies(dto -> {
                assertThat(dto.isEnabled()).isFalse();
                assertThat(dto.getRecordedByUserId()).isEqualTo(actorId);
                assertThat(dto.getRecordedAt()).isBetween(before, java.time.LocalDateTime.now());
            });
        verify(userRepository).lockForNotificationPreferences(account.getId());
    }

    @Test
    void changingNothingDoesNotInventNewProvenanceForALegacyChoice() {
        var legacy = row(NotificationType.LAB_RESULT, NotificationChannel.SMS, false);
        stored.add(legacy);
        service.update(patient.getId(), hospitalId,
            List.of(update(NotificationType.LAB_RESULT, NotificationChannel.SMS, false)), actorId);
        assertThat(legacy.getRecordedByUserId()).isNull();
        assertThat(legacy.getRecordedAt()).isNull();
    }

    @Test
    void anUnresolvableRecorderCannotWriteAChoice() {
        assertThatThrownBy(() -> service.update(patient.getId(), hospitalId,
            List.of(update(NotificationType.LAB_RESULT, NotificationChannel.SMS, false)), null))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(preferenceRepository, never()).saveAll(anyList());
    }

    private NotificationPreference row(NotificationType type, NotificationChannel channel, boolean enabled) {
        return NotificationPreference.builder().user(account).notificationType(type).channel(channel)
            .enabled(enabled).build();
    }

    private static NotificationPreferenceUpdateDTO update(NotificationType type, NotificationChannel channel,
                                                          boolean enabled) {
        return new NotificationPreferenceUpdateDTO(type, channel, enabled);
    }
}
