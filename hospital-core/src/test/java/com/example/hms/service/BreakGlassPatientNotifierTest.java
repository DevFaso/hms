package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.service.i18n.PatientLocaleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D410: break-the-glass overrides an opt-out, and the patient is told. */
@ExtendWith(MockitoExtension.class)
class BreakGlassPatientNotifierTest {

    @Mock private PatientRecordSharingOptOutRepository optOutRepository;
    @Mock private NotificationService notificationService;
    @Mock private MessageSource messageSource;
    @Mock private PatientLocaleResolver patientLocaleResolver;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @InjectMocks private BreakGlassPatientNotifier notifier;

    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        User portal = new User();
        portal.setUsername("patient.a3");
        patient.setUser(portal);
        hospital = Hospital.builder().name("Hôpital B").build();
    }

    @Test
    void anOptedOutPatientIsNotifiedInTheirInbox() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(true);
        when(patientLocaleResolver.resolve(eq(patient), any())).thenReturn(Locale.FRENCH);
        when(messageSource.getMessage(eq(BreakGlassPatientNotifier.MESSAGE_KEY), any(), eq(Locale.FRENCH)))
            .thenReturn("Accès d'urgence à Hôpital B");

        assertThat(notifier.notifyIfOptedOut(patient, hospital)).isTrue();

        verify(notificationService).createNotification("Accès d'urgence à Hôpital B", "patient.a3",
            BreakGlassPatientNotifier.NOTIFICATION_TYPE);
    }

    @Test
    void aPatientWhoDidNotOptOutGetsNoExtraMessage() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(false);

        assertThat(notifier.notifyIfOptedOut(patient, hospital)).isFalse();

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void aNotificationFailureNeverBlocksEmergencyAccess() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(true);
        when(patientLocaleResolver.resolve(eq(patient), any())).thenThrow(new IllegalStateException("down"));

        assertThat(notifier.notifyIfOptedOut(patient, hospital)).isFalse();
    }

    @Test
    void theNotificationIsSavedOnlyAfterTheDeclarationCommitsAndAFailureThereIsSwallowed() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(true);
        when(patientLocaleResolver.resolve(eq(patient), any())).thenReturn(Locale.FRENCH);
        when(messageSource.getMessage(eq(BreakGlassPatientNotifier.MESSAGE_KEY), any(), eq(Locale.FRENCH)))
            .thenReturn("msg");
        when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("save failed"));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(notifier.notifyIfOptedOut(patient, hospital)).isTrue();
            // Nothing touches the declare transaction: the save is deferred.
            verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());

            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(notificationService).createNotification("msg", "patient.a3",
                BreakGlassPatientNotifier.NOTIFICATION_TYPE);
            // ...in its own REQUIRES_NEW transaction, never the declaration's.
            verify(transactionManager).getTransaction(org.mockito.ArgumentMatchers.argThat(def ->
                def.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
