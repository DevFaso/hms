package com.example.hms.service.notification;

import com.example.hms.BaseIT;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delivery ledger against the test database (V312; EntitySchemaValidationIT checks the migration): the
 * idempotency key is enforced by the database, the claim is exclusive, the manual retry is scoped
 * to the hospital, and one sweep round trip through the real queries. SMS and mail transports are
 * mocks: nothing is ever sent.
 */
@DisplayName("Patient notification ledger (database)")
class PatientNotificationLedgerIT extends BaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));

    @Autowired private PatientNotificationDeliveryRepository repository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientChannelConsent consent;
    @Autowired private PatientNotificationDispatchService dispatchService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private Hospital hospital;
    private Hospital otherHospital;
    private Patient patient;
    private User account;

    @BeforeEach
    void seed() {
        hospital = hospital();
        otherHospital = hospital();
        int n = SEQ.incrementAndGet();
        account = userRepository.save(User.builder()
            .username("pnd-it-user-" + n)
            .passwordHash("noop")
            .email("pnd-it-user-" + n + "@hospital.test")
            .firstName("Awa")
            .lastName("PND-IT-" + n)
            .phoneNumber("+22671" + String.format("%06d", n % 1_000_000))
            .build());
        patient = patientRepository.save(Patient.builder()
            .user(account)
            .firstName("Awa")
            .lastName("PND-IT-" + n)
            .dateOfBirth(java.time.LocalDate.of(1990, 1, 1))
            .gender("FEMALE")
            .phoneNumberPrimary("+22670" + String.format("%06d", n % 1_000_000))
            .email("pnd-it-" + n + "@patient.test")
            .hospitalId(hospital.getId())
            .build());
    }

    private Hospital hospital() {
        int n = SEQ.incrementAndGet();
        return hospitalRepository.save(Hospital.builder()
            .name("PND-IT Hospital " + n)
            .code("PND" + n)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+22656" + String.format("%06d", n % 1_000_000))
            .email("pnd" + n + "@hospital.test")
            .build());
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from clinical.patient_notification_deliveries where patient_id = ?", patient.getId());
        patientRepository.deleteById(patient.getId());
        userRepository.deleteById(account.getId());
        hospitalRepository.deleteById(hospital.getId());
        hospitalRepository.deleteById(otherHospital.getId());
    }

    private PatientNotificationLedger ledgerWithRealTransports() {
        SmsService sms = Mockito.mock(SmsService.class);
        Mockito.when(sms.deliversRealSms()).thenReturn(true);
        EmailService mail = Mockito.mock(EmailService.class);
        Mockito.when(mail.deliversRealEmail()).thenReturn(true);
        return new PatientNotificationLedger(repository, patientRepository, consent, sms, mail, clock,
            transactionManager);
    }

    @Test
    @DisplayName("queued twice, stored once per channel; nothing of the message, number or address on disk")
    void queuedOncePerChannel() {
        PatientNotificationLedger ledger = ledgerWithRealTransports();
        UUID orderId = UUID.randomUUID();

        assertThat(ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospital.getId(), patient.getId(),
            orderId)).containsExactly(NotificationChannel.SMS, NotificationChannel.EMAIL);
        assertThat(ledger.enqueue(PatientNotificationEvent.LAB_RESULT_AVAILABLE, hospital.getId(), patient.getId(),
            orderId)).isEmpty();

        List<String> rows = repository.findAll().stream()
            .filter(r -> r.getPatientId().equals(patient.getId()))
            .map(r -> String.join("|", r.getEventType().name(), String.valueOf(r.getSubjectId()),
                r.getChannel().name(), r.getIdempotencyKey(), r.getStatus().name(), String.valueOf(r.getErrorCode())))
            .toList();
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row)
            .doesNotContain(patient.getPhoneNumberPrimary().substring(4))
            .doesNotContain("@patient.test"));
        List<String> columns = jdbc.queryForList("select lower(column_name) from information_schema.columns "
            + "where lower(table_schema) = 'clinical' and lower(table_name) = 'patient_notification_deliveries'",
            String.class);
        assertThat(columns).contains("idempotency_key", "error_code")
            .doesNotContain("phone", "phone_number", "email", "recipient", "body", "message");
    }

    @Test
    @DisplayName("the database refuses a second row with the same key")
    void theKeyIsUnique() {
        UUID subject = UUID.randomUUID();
        repository.saveAndFlush(row(subject, hospital.getId()));

        PatientNotificationDelivery duplicate = row(subject, hospital.getId());
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("of two claims on one due row exactly one wins")
    void theClaimIsExclusive() {
        PatientNotificationDelivery saved = repository.saveAndFlush(row(UUID.randomUUID(), hospital.getId()));
        LocalDateTime now = LocalDateTime.now(clock).plusSeconds(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<NotificationDeliveryStatus> open = PatientNotificationDispatchService.OPEN;

        Integer first = tx.execute(s -> repository.claim(saved.getId(), open, 5, now, now.plusMinutes(5)));
        Integer second = tx.execute(s -> repository.claim(saved.getId(), open, 5, now, now.plusMinutes(5)));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("an outcome is written only by the sweep that still holds the claim")
    void theOutcomeNeedsTheClaimToken() {
        PatientNotificationDelivery saved = repository.saveAndFlush(row(UUID.randomUUID(), hospital.getId()));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<NotificationDeliveryStatus> open = PatientNotificationDispatchService.OPEN;
        LocalDateTime firstClaim = LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        LocalDateTime secondClaim = firstClaim.plusMinutes(6);
        tx.execute(s -> repository.claim(saved.getId(), open, 5, firstClaim, firstClaim.plusMinutes(5)));
        // The first send outlived its lease: a second sweep claimed the row again.
        tx.execute(s -> repository.claim(saved.getId(), open, 5, secondClaim, secondClaim.plusMinutes(5)));

        Integer stale = tx.execute(s -> repository.markFailure(saved.getId(), firstClaim, open,
            NotificationDeliveryStatus.FAILED, "OUTCOME_UNKNOWN", firstClaim, firstClaim));
        Integer current = tx.execute(s -> repository.markSent(saved.getId(), secondClaim, open,
            NotificationDeliveryStatus.SENT, secondClaim));

        assertThat(stale).as("the first sweep lost its claim and writes nothing").isZero();
        assertThat(current).isEqualTo(1);
        PatientNotificationDelivery reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(reloaded.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("a FAILED row is requeued only from its own hospital, and only once")
    void requeueIsScopedAndOnce() {
        PatientNotificationDelivery row = row(UUID.randomUUID(), hospital.getId());
        row.setStatus(NotificationDeliveryStatus.FAILED);
        row.setAttempts(5);
        row.setErrorCode("PROVIDER_UNREACHABLE");
        PatientNotificationDelivery saved = repository.saveAndFlush(row);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        LocalDateTime now = LocalDateTime.now(clock);
        UUID admin = UUID.randomUUID();

        Integer foreign = tx.execute(s -> repository.requeueFailed(saved.getId(), otherHospital.getId(),
            NotificationDeliveryStatus.FAILED, NotificationDeliveryStatus.PENDING, now, admin));
        Integer own = tx.execute(s -> repository.requeueFailed(saved.getId(), hospital.getId(),
            NotificationDeliveryStatus.FAILED, NotificationDeliveryStatus.PENDING, now, admin));
        Integer again = tx.execute(s -> repository.requeueFailed(saved.getId(), hospital.getId(),
            NotificationDeliveryStatus.FAILED, NotificationDeliveryStatus.PENDING, now, admin));

        assertThat(foreign).isZero();
        assertThat(own).isEqualTo(1);
        assertThat(again).isZero();
        PatientNotificationDelivery reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getManualRetries()).isEqualTo(1);
        assertThat(reloaded.getLastRetriedBy()).isEqualTo(admin);
        assertThat(repository.findByIdAndHospitalId(saved.getId(), otherHospital.getId())).isEmpty();
        assertThat(repository.searchForHospital(otherHospital.getId(),
            List.of(NotificationDeliveryStatus.values()), PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(repository.countByStatusForHospital(hospital.getId())).isNotEmpty();
    }

    @Test
    @DisplayName("one sweep round trip: a result that no longer exists is SKIPPED and nothing is sent")
    void sweepRoundTrip() {
        PatientNotificationDelivery saved = repository.saveAndFlush(row(UUID.randomUUID(), hospital.getId()));

        dispatchService.dispatchPending();

        PatientNotificationDelivery reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED);
        assertThat(reloaded.getErrorCode()).isEqualTo("SUBJECT_WITHDRAWN");
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }

    private PatientNotificationDelivery row(UUID subject, UUID hospitalId) {
        return PatientNotificationDelivery.builder()
            .hospitalId(hospitalId)
            .patientId(patient.getId())
            .eventType(PatientNotificationEvent.LAB_RESULT_AVAILABLE)
            .subjectId(subject)
            .channel(NotificationChannel.SMS)
            .idempotencyKey(PatientNotificationDelivery.keyOf(PatientNotificationEvent.LAB_RESULT_AVAILABLE, subject,
                NotificationChannel.SMS))
            .nextAttemptAt(LocalDateTime.now(clock).minusSeconds(5))
            .build();
    }
}
