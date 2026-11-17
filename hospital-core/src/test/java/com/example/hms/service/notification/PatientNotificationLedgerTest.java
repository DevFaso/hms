package com.example.hms.service.notification;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.model.User;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Patient notification ledger: what is queued, once")
class PatientNotificationLedgerTest {

    @Mock private PatientNotificationDeliveryRepository repository;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientChannelConsent consent;
    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private PlatformTransactionManager transactionManager;

    private PatientNotificationLedger ledger;
    private Patient patient;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ledger = new PatientNotificationLedger(repository, patientRepository, consent, smsService, emailService,
            Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC), transactionManager);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setEmail("awa@example.com");
        User user = new User();
        user.setId(UUID.randomUUID());
        patient.setUser(user);
        lenient().when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(emailService.deliversRealEmail()).thenReturn(true);
        lenient().when(consent.allows(any(), any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("a result queues one SMS and one email row, keyed event:subject:channel")
    void queuesBothChannels() {
        List<NotificationChannel> queued = ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospitalId,
            patient.getId(), subjectId);

        assertThat(queued).containsExactly(NotificationChannel.SMS, NotificationChannel.EMAIL);
        ArgumentCaptor<PatientNotificationDelivery> saved = ArgumentCaptor.forClass(PatientNotificationDelivery.class);
        verify(repository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(PatientNotificationDelivery::getIdempotencyKey).containsExactly(
            "LAB_RESULT_AVAILABLE:" + subjectId + ":SMS", "LAB_RESULT_AVAILABLE:" + subjectId + ":EMAIL");
        assertThat(saved.getAllValues()).allSatisfy(row -> {
            assertThat(row.getHospitalId()).isEqualTo(hospitalId);
            assertThat(row.getPatientId()).isEqualTo(patient.getId());
            assertThat(row.getNextAttemptAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("an existing key queues nothing: a re-run never sends twice")
    void anExistingKeyIsNotQueuedAgain() {
        when(repository.existsByIdempotencyKey(anyString())).thenReturn(true);

        assertThat(ledger.enqueue(PatientNotificationEvent.APPOINTMENT_REMINDER, hospitalId, patient.getId(),
            subjectId)).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a concurrent insert of the same key is a duplicate, not an error")
    void aRacedInsertIsADuplicate() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_pnd_idempotency_key"));

        assertThat(ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospitalId, patient.getId(),
            subjectId)).isEmpty();
    }

    @Test
    @DisplayName("no SMS row while the transport is the logging mock")
    void noSmsOnAMockTransport() {
        when(smsService.deliversRealSms()).thenReturn(false);

        assertThat(ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospitalId, patient.getId(),
            subjectId)).containsExactly(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("a channel the patient turned off is not queued")
    void honoursTheStoredPreference() {
        when(consent.allows(patient.getUser().getId(), NotificationType.LAB_RESULT, NotificationChannel.SMS))
            .thenReturn(false);

        assertThat(ledger.enqueue(PatientNotificationEvent.IMAGING_RESULT_AVAILABLE, hospitalId, patient.getId(),
            subjectId)).containsExactly(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("no number and no address: nothing queued")
    void noContactNoRow() {
        patient.setPhoneNumberPrimary(" ");
        patient.setEmail(null);

        assertThat(ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospitalId, patient.getId(),
            subjectId)).isEmpty();
    }

    @Test
    @DisplayName("a booking confirmation is SMS only: its email has been sent at booking since before V312")
    void confirmationIsSmsOnly() {
        assertThat(ledger.enqueue(PatientNotificationEvent.APPOINTMENT_CONFIRMATION, hospitalId, patient.getId(),
            subjectId)).containsExactly(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("a failure while queueing is swallowed: the booking or release is never failed by it")
    void neverThrows() {
        when(patientRepository.findByIdUnscoped(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospitalId, patient.getId(),
            subjectId)).isEmpty();
    }

    @Test
    @DisplayName("only a live booking is confirmed; a PENDING follow-up or a cancelled one is not")
    void confirmsOnlyLiveBookings() {
        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setPatient(patient);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        appointment.setHospital(hospital);
        appointment.setAppointmentDate(LocalDate.of(2026, 11, 20));
        appointment.setStartTime(LocalTime.of(9, 0));

        appointment.setStatus(AppointmentStatus.PENDING);
        ledger.confirmBooking(appointment);
        appointment.setStatus(AppointmentStatus.CANCELLED);
        ledger.confirmBooking(appointment);
        verify(repository, never()).saveAndFlush(any());

        // No transaction is active in a unit test, so the after-commit request runs at once.
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        ledger.confirmBooking(appointment);
        ArgumentCaptor<PatientNotificationDelivery> saved = ArgumentCaptor.forClass(PatientNotificationDelivery.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo(PatientNotificationEvent.APPOINTMENT_CONFIRMATION);
        assertThat(saved.getValue().getSubjectId()).isEqualTo(appointment.getId());
    }
}
