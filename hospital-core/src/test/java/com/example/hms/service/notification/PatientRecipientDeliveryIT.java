package com.example.hms.service.notification;

import com.example.hms.BaseIT;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientRecipientChallengeRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real reservation/confirmation transactions, mocked transports only: no SMS or mail leaves this test. */
class PatientRecipientDeliveryIT extends BaseIT {
    @Autowired PatientRecipientChallengeService service;
    @Autowired PatientRecipientChallengeRepository challenges;
    @Autowired PatientRepository patients;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SmsService sms;
    @MockitoBean EmailService email;
    Patient patient;
    Hospital hospital;
    User account;
    UUID actor = UUID.randomUUID();

    @BeforeEach void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        hospital = hospitals.save(Hospital.builder().name("cdx Recipient hospital " + suffix)
            .code("RV" + suffix).city("Ouagadougou").country("Burkina Faso").build());
        account = users.save(User.builder().username("cdx-recipient-" + suffix).passwordHash("noop")
            .email("cdx-user-" + suffix + "@example.invalid").firstName("cdx").lastName("Recipient " + suffix)
            .phoneNumber("+22670000000").build());
        actor = account.getId();
        patient = patients.save(Patient.builder().user(account).firstName("cdx").lastName("Recipient " + suffix)
            .dateOfBirth(LocalDate.of(1990, 1, 1)).gender("FEMALE").hospitalId(hospital.getId())
            .phoneNumberPrimary("+22670000000").email("cdx-" + suffix + "@example.invalid").build());
        when(sms.deliversRealSms()).thenReturn(true);
        when(email.deliversRealEmail()).thenReturn(true);
    }
    @AfterEach void clean() {
        if (patient != null) {
            for (var channel : new NotificationChannel[] {NotificationChannel.SMS, NotificationChannel.EMAIL})
                challenges.findByPatientIdAndChannel(patient.getId(), channel).ifPresent(challenges::delete);
            patients.deleteById(patient.getId());
        }
        if (account != null) users.deleteById(account.getId());
        if (hospital != null) hospitals.deleteById(hospital.getId());
    }

    @Test void reservationIsCommittedAndDeliveryHasNoActiveTransactionBeforeConfirmation() {
        AtomicReference<String> code = new AtomicReference<>();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var row = challenges.findByPatientIdAndChannel(patient.getId(), NotificationChannel.SMS).orElseThrow();
            assertThat(row.getRequestCount()).isEqualTo(1); assertThat(row.getCodeHash()).isNotBlank();
            var match = Pattern.compile("\\b([0-9]{6})\\b").matcher(call.<String>getArgument(1));
            assertThat(match.find()).isTrue(); code.set(match.group(1)); return null;
        }).when(sms).send(eq(patient.getPhoneNumberPrimary()), anyString());
        service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH);
        assertThat(service.confirm(patient.getId(), NotificationChannel.SMS, actor, code.get()).verified()).isTrue();
        assertThat(patients.findByIdUnscoped(patient.getId()).orElseThrow().getVerifiedSmsRecipient())
            .isEqualTo(patient.getPhoneNumberPrimary());
        verifyNoInteractions(email);
    }

    @Test void synchronousEmailFailureInvalidatesItsCodeAndPreservesItsBudgetEvenIfTheCallerRollsBack() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(challenges.findByPatientIdAndChannel(patient.getId(), NotificationChannel.EMAIL)
                .orElseThrow().getRequestCount()).isEqualTo(1);
            throw new IllegalStateException("cdx-provider-down");
        }).when(email).sendWithAttachment(any(), any(), any(), any(), any(), isNull(), isNull(), isNull());
        var outer = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> outer.execute(status ->
            service.request(patient.getId(), NotificationChannel.EMAIL, actor, Locale.FRENCH)))
            .isInstanceOf(BusinessException.class).hasMessageNotContaining("cdx-provider-down");
        var failed = challenges.findByPatientIdAndChannel(patient.getId(), NotificationChannel.EMAIL).orElseThrow();
        assertThat(failed.getCodeHash()).isNull(); assertThat(failed.getRequestCount()).isEqualTo(1);
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        verify(email).sendWithAttachment(any(), any(), any(), any(), any(), isNull(), isNull(), isNull());
    }
}
