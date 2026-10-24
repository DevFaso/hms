package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @InjectMocks private BreakGlassPatientNotifier notifier;

    private static final String EXPECTED_FR = "Un accès d'urgence à votre dossier a été ouvert à Hôpital B, bien que vous ayez fermé le partage de votre dossier. Il est enregistré et figure dans l'historique des accès.";

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

        assertThat(notifier.notifyIfOptedOut(patient, hospital)).isTrue();

        // QA D665: the key and the hospital are stored, so the patient reads it
        // in their own language; the French text is stored alongside.
        verify(notificationService).createNotification(eq(BreakGlassPatientNotifier.MESSAGE_KEY),
            org.mockito.ArgumentMatchers.argThat(args -> args.length == 1 && "Hôpital B".equals(args[0])),
            eq("patient.a3"), eq(BreakGlassPatientNotifier.NOTIFICATION_TYPE));
        verify(notificationService).created(EXPECTED_FR, "patient.a3",
            BreakGlassPatientNotifier.NOTIFICATION_TYPE);
    }

    @Test
    void aPatientWhoDidNotOptOutGetsNoExtraMessage() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(false);

        assertThat(notifier.notifyIfOptedOut(patient, hospital)).isFalse();

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void aNotificationFailureNeverBlocksEmergencyAccess() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(true);
        Hospital broken = org.mockito.Mockito.mock(Hospital.class);
        when(broken.getName()).thenThrow(new IllegalStateException("down"));

        assertThat(notifier.notifyIfOptedOut(patient, broken)).isFalse();
    }

    @Test
    void theNotificationIsSavedOnlyAfterTheDeclarationCommitsAndAFailureThereIsSwallowed() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())).thenReturn(true);
        when(notificationService.created(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("save failed"));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(notifier.notifyIfOptedOut(patient, hospital)).isTrue();
            // Nothing touches the declare transaction: the save is deferred.
            verify(notificationService, never()).created(anyString(), anyString(), anyString());

            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(notificationService).created(EXPECTED_FR, "patient.a3",
                BreakGlassPatientNotifier.NOTIFICATION_TYPE);
            // ...in its own REQUIRES_NEW transaction, never the declaration's.
            verify(transactionManager).getTransaction(org.mockito.ArgumentMatchers.argThat(def ->
                def.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
