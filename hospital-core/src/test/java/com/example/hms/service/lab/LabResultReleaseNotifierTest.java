package com.example.hms.service.lab;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D938 (the patient is told a result is ready) and D931 (the clinician is told of a correction). */
@ExtendWith(MockitoExtension.class)
class LabResultReleaseNotifierTest {

    @Mock private NotificationService notificationService;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private LabResultReleaseNotifier notifier;

    private User portalUser;
    private LabOrder order;

    @BeforeEach
    void setUp() {
        portalUser = new User();
        portalUser.setId(UUID.randomUUID());
        portalUser.setUsername("awa.patient");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Ouedraogo");
        patient.setUser(portalUser);
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Hémoglobine");
        order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(definition);
    }

    private LabResult released(String value, String unit) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setResultUnit(unit);
        result.setReleased(true);
        return result;
    }

    @Test
    @DisplayName("QA D938 — a released result notifies the patient with the test name only, never the value")
    void aReleasedResultNotifiesThePatient() {
        LabResult result = released("6.5", "g/dL");
        when(preferenceRepository.findByUser_IdAndNotificationType(portalUser.getId(), NotificationType.LAB_RESULT))
            .thenReturn(List.of());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        notifier.patientResultReleased(result);

        verify(notificationService).createNotification(eq("lab.result.patient.available"), args.capture(),
            eq("awa.patient"), eq("LAB_RESULT"), eq(result.getId()));
        assertThat(args.getValue()).containsExactly("Hémoglobine");
        assertThat(List.of(args.getValue()).toString()).doesNotContain("6.5").doesNotContain("6,5");
    }

    @Test
    @DisplayName("QA D938 — an explicit disabled in-app preference for lab results turns the notice off")
    void aDisabledPreferenceIsHonoured() {
        NotificationPreference off = new NotificationPreference();
        off.setChannel(NotificationChannel.IN_APP);
        off.setNotificationType(NotificationType.LAB_RESULT);
        off.setEnabled(false);
        when(preferenceRepository.findByUser_IdAndNotificationType(portalUser.getId(), NotificationType.LAB_RESULT))
            .thenReturn(List.of(off));

        notifier.patientResultReleased(released("Positif", null));

        verify(notificationService, never()).createNotification(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("QA D938 — a patient with no portal account is a no-op, never a failed release")
    void aPatientWithoutAnAccountIsANoOp() {
        order.getPatient().setUser(null);

        assertThatCode(() -> notifier.patientResultReleased(released("4.1", "mmol/L"))).doesNotThrowAnyException();

        verifyNoInteractions(notificationService, preferenceRepository);
    }

    @Test
    @DisplayName("QA D938 — an unreleased result tells the patient nothing")
    void anUnreleasedResultIsNotAnnounced() {
        LabResult draft = released("4.1", "mmol/L");
        draft.setReleased(false);

        notifier.patientResultReleased(draft);

        verifyNoInteractions(notificationService, preferenceRepository);
    }

    @Test
    @DisplayName("QA D938 — a failure writing the notice is logged, never thrown into the release")
    void aFailedNoticeDoesNotPropagate() {
        when(preferenceRepository.findByUser_IdAndNotificationType(portalUser.getId(), NotificationType.LAB_RESULT))
            .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> notifier.patientResultReleased(released("4.1", "mmol/L"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("QA D931 — a released correction tells the ordering clinician the old and the new value")
    void aCorrectionNamesTheOldAndTheNewValue() {
        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setUsername("dr.sawadogo");
        Staff staff = new Staff();
        staff.setUser(doctor);
        order.setOrderingStaff(staff);
        LabResult original = released("10.4", "g/dL");
        LabResult correction = released("6.5", "g/dL");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        notifier.correctionReleased(correction, original);

        verify(notificationService).createNotification(eq("lab.result.correction.notification"), args.capture(),
            eq("dr.sawadogo"), eq("LAB_RESULT"), eq(correction.getId()));
        assertThat(args.getValue()).containsExactly("Hémoglobine", "Awa Ouedraogo", "10,4 g/dL", "6,5 g/dL");
    }
}
