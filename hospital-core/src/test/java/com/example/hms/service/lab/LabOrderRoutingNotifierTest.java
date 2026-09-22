package com.example.hms.service.lab;

import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocales;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit gap B1: the performing laboratory's lab users hear about an order
 * sent to them, once each, in the staff locale, and never for an in-house
 * order. Outside a transaction {@code TransactionCallbacks.afterCommit} runs
 * inline, and the mocked transaction manager makes each REQUIRES_NEW
 * delivery a plain call, which is what lets these tests observe it directly.
 */
@ExtendWith(MockitoExtension.class)
class LabOrderRoutingNotifierTest {

    @Mock private StaffRepository staffRepository;
    @Mock private NotificationService notificationService;
    @Mock private MessageSource messageSource;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private LabOrderRoutingNotifier notifier;

    private Hospital ordering;
    private Hospital performing;
    private LabOrder order;

    @BeforeEach
    void setUp() {
        ordering = hospital("Ordering Hospital");
        performing = hospital("Central Laboratory");
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("CBC");
        order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(ordering);
        order.setLabTestDefinition(definition);
    }

    @Test
    void notifiesEveryLabUserAtThePerformingHospitalOnce() {
        order.setPerformingHospital(performing);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(performing.getId(), "ROLE_LAB_SCIENTIST"))
            .thenReturn(List.of("sci1", "shared"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(performing.getId(), "ROLE_LAB_TECHNICIAN"))
            .thenReturn(List.of("tech1"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(performing.getId(), "ROLE_LAB_MANAGER"))
            .thenReturn(List.of("shared"));
        when(messageSource.getMessage(eq(LabOrderRoutingNotifier.MESSAGE_KEY), any(), eq(NotificationLocales.STAFF)))
            .thenReturn("Ordering Hospital sent your laboratory a new order: CBC.");

        notifier.notifyPerformingLab(order);

        verify(notificationService).createNotification(
            "Ordering Hospital sent your laboratory a new order: CBC.", "sci1", LabOrderRoutingNotifier.NOTIFICATION_TYPE);
        verify(notificationService).createNotification(
            "Ordering Hospital sent your laboratory a new order: CBC.", "shared", LabOrderRoutingNotifier.NOTIFICATION_TYPE);
        verify(notificationService).createNotification(
            "Ordering Hospital sent your laboratory a new order: CBC.", "tech1", LabOrderRoutingNotifier.NOTIFICATION_TYPE);
    }

    @Test
    void messageNamesTheOrderingHospitalAndTheTestOnly() {
        order.setPerformingHospital(performing);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(eq(performing.getId()), any()))
            .thenReturn(List.of("sci1"));
        when(messageSource.getMessage(any(), any(), any())).thenReturn("msg");

        notifier.notifyPerformingLab(order);

        verify(messageSource).getMessage(LabOrderRoutingNotifier.MESSAGE_KEY,
            new Object[]{"Ordering Hospital", "CBC"}, NotificationLocales.STAFF);
    }

    @Test
    void inHouseOrderNotifiesNobody() {
        notifier.notifyPerformingLab(order);

        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), any());
        verify(notificationService, never()).createNotification(any(), any(), any());
    }

    @Test
    void laboratoryWithoutLabUsersIsSkippedQuietly() {
        order.setPerformingHospital(performing);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(eq(performing.getId()), any()))
            .thenReturn(List.of());

        notifier.notifyPerformingLab(order);

        verify(notificationService, never()).createNotification(any(), any(), any());
    }

    @Test
    void aFailingRecipientDoesNotStopTheOthersNorTheOrder() {
        order.setPerformingHospital(performing);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(eq(performing.getId()), any()))
            .thenReturn(List.of("broken", "sci2"));
        when(messageSource.getMessage(any(), any(), any())).thenReturn("msg");
        when(notificationService.createNotification("msg", "broken", LabOrderRoutingNotifier.NOTIFICATION_TYPE))
            .thenThrow(new IllegalStateException("websocket down"));

        notifier.notifyPerformingLab(order);

        verify(notificationService).createNotification("msg", "sci2", LabOrderRoutingNotifier.NOTIFICATION_TYPE);
    }

    @Test
    void aFailingLookupNeverPropagates() {
        order.setPerformingHospital(performing);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(eq(performing.getId()), any()))
            .thenThrow(new IllegalStateException("db down"));

        notifier.notifyPerformingLab(order);

        verify(notificationService, never()).createNotification(any(), any(), any());
    }

    private static Hospital hospital(String name) {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName(name);
        return hospital;
    }
}
