package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** QA D4149 (r81): the prescriber is told when their lab order is voided or cancelled, with the reason. */
@ExtendWith(MockitoExtension.class)
@DisplayName("QA D4149: a voided or cancelled lab order tells its prescriber")
class LabOrderWithdrawalNoticeTest {

    @Mock private NotificationService notificationService;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private com.example.hms.repository.LabOrderRepository labOrderRepository;

    @InjectMocks
    private LabResultReleaseNotifier notifier;

    private LabOrder order;
    private User doctor;

    @BeforeEach
    void setUp() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Issouf");
        patient.setLastName("Sawadogo");
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Kaliémie");
        doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setUsername("dr.kabore");
        Staff staff = new Staff();
        staff.setUser(doctor);
        order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(definition);
        order.setOrderingStaff(staff);
        order.setCancellationReason("Mauvais patient prélevé");
    }

    @Test
    void aVoidTellsThePrescriberTheResultIsWithdrawn_withTheReason() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        notifier.prescriberOrderWithdrawn(order, true, UUID.randomUUID());

        verify(notificationService).createNotification(eq("lab.order.voided.notification"), args.capture(),
            eq("dr.kabore"), eq("LAB_RESULT"));
        assertThat(args.getValue()).containsExactly("Kaliémie", "Issouf Sawadogo", "Mauvais patient prélevé");
        com.example.hms.service.i18n.NotificationLocalizer localizer = new com.example.hms.service.i18n.NotificationLocalizer(
            com.example.hms.i18n.TestMessageSources.bundles());
        List<Object> stored = com.example.hms.service.i18n.NotificationLocalizer.storable(args.getValue());
        assertThat(localizer.render("lab.order.voided.notification", stored, Locale.FRENCH))
            .contains("Kaliémie").contains("Issouf Sawadogo").contains("Motif : Mauvais patient prélevé")
            .contains("saisi par erreur");
        assertThat(localizer.render("lab.order.voided.notification", stored, Locale.ENGLISH))
            .contains("entered in error").contains("Reason: Mauvais patient prélevé");
    }

    /** Review of QA D4149: a void of an order that never had a result does not speak of « this result ». */
    @Test
    void aVoidOfAnOrderWithNoResultSpeaksOfTheOrder() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        notifier.prescriberOrderWithdrawn(order, true, false, UUID.randomUUID());

        verify(notificationService).createNotification(eq("lab.order.voidedNoResult.notification"), args.capture(),
            eq("dr.kabore"), eq("LAB_RESULT"));
        com.example.hms.service.i18n.NotificationLocalizer localizer = new com.example.hms.service.i18n.NotificationLocalizer(
            com.example.hms.i18n.TestMessageSources.bundles());
        List<Object> stored = com.example.hms.service.i18n.NotificationLocalizer.storable(args.getValue());
        assertThat(localizer.render("lab.order.voidedNoResult.notification", stored, Locale.FRENCH))
            .contains("Kaliémie").contains("avant tout résultat").doesNotContain("ce résultat");
    }

    @Test
    void aCancellationTellsThePrescriber_withTheReason() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        notifier.prescriberOrderWithdrawn(order, false, UUID.randomUUID());

        verify(notificationService).createNotification(eq("lab.order.cancelled.notification"), args.capture(),
            eq("dr.kabore"), eq("LAB_RESULT"));
        com.example.hms.service.i18n.NotificationLocalizer localizer = new com.example.hms.service.i18n.NotificationLocalizer(
            com.example.hms.i18n.TestMessageSources.bundles());
        assertThat(localizer.render("lab.order.cancelled.notification",
            com.example.hms.service.i18n.NotificationLocalizer.storable(args.getValue()), Locale.FRENCH))
            .isEqualTo("Demande d’analyse annulée : Kaliémie pour Issouf Sawadogo. Motif : Mauvais patient prélevé.");
    }

    @Test
    void whoeverWithdrawsTheirOwnOrderIsNotToldOfIt() {
        notifier.prescriberOrderWithdrawn(order, false, doctor.getId());

        verifyNoInteractions(notificationService);
    }

    @Test
    void anOrderWithNoPrescriberAccountTellsNobody() {
        order.setOrderingStaff(null);

        notifier.prescriberOrderWithdrawn(order, true, UUID.randomUUID());

        verifyNoInteractions(notificationService);
    }
}
