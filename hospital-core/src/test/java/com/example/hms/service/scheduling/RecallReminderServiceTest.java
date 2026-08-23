package com.example.hms.service.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.hms.enums.RecallStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Recall notice sweep (P3 #22) — the V112 exactly-once stamp idiom. */
@ExtendWith(MockitoExtension.class)
class RecallReminderServiceTest {

    @Mock private PatientRecallRepository recallRepository;
    @Mock private PatientOutreachNotifier outreachNotifier;
    @Mock private MessageSource messageSource;

    private RecallReminderService service;

    private Patient patient;
    private PatientRecall recall;

    @BeforeEach
    void setUp() {
        service = new RecallReminderService(recallRepository, outreachNotifier, messageSource);
        ReflectionTestUtils.setField(service, "leadDays", 14L);
        ReflectionTestUtils.setField(service, "outreachLocale", "fr");

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU Yalgado");

        recall = PatientRecall.builder()
            .patient(patient)
            .hospital(hospital)
            .dueDate(LocalDate.now().plusDays(7))
            .reason("Post-op review")
            .build();
        recall.setId(UUID.randomUUID());

        lenient().when(messageSource.getMessage(eq("sms.recall.due"), any(), any(Locale.class)))
            .thenReturn("Une visite de suivi vous attend à CHU Yalgado.");
        lenient().when(recallRepository.save(any(PatientRecall.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(recallRepository.findAwaitingNotification(any()))
            .thenReturn(List.of(recall));
    }

    @Test
    void notifiesAndStampsTheRecall() {
        when(outreachNotifier.notifyPatient(eq(patient), any())).thenReturn(true);

        int notified = service.sendDueRecallNotices();

        assertThat(notified).isEqualTo(1);
        assertThat(recall.getNotifiedAt()).isNotNull();
        assertThat(recall.getStatus()).isEqualTo(RecallStatus.NOTIFIED);
    }

    @Test
    void stampsEvenWhenNoChannelDispatched() {
        // Otherwise the sweep re-evaluates the same row forever.
        when(outreachNotifier.notifyPatient(eq(patient), any())).thenReturn(false);

        int notified = service.sendDueRecallNotices();

        assertThat(notified).isZero();
        assertThat(recall.getNotifiedAt()).isNotNull();
        assertThat(recall.getStatus()).isEqualTo(RecallStatus.NOTIFIED);
    }

    @Test
    void oneBadRowNeverKillsTheSweep() {
        PatientRecall broken = PatientRecall.builder().build(); // null patient/hospital
        broken.setId(UUID.randomUUID());
        when(recallRepository.findAwaitingNotification(any()))
            .thenReturn(List.of(broken, recall));
        when(outreachNotifier.notifyPatient(any(), any()))
            .thenAnswer(inv -> inv.getArgument(0) == patient);

        int notified = service.sendDueRecallNotices();

        assertThat(notified).isEqualTo(1);
        assertThat(recall.getNotifiedAt()).isNotNull();
    }
}
