package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NotificationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LowStockAlertDeliveryTest {
    @Mock private UserRoleHospitalAssignmentRepository assignments;
    @Mock private NotificationService notifications;
    @Mock private JdbcTemplate jdbc;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactions;
    private LowStockAlertDelivery service;
    private InventoryItem item;
    @Mock private com.example.hms.repository.pharmacy.InventoryItemRepository items;
    private User recipient;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

    @BeforeEach
    void setUp() {
        lenient().when(transactions.getTransaction(any())).thenAnswer(call -> new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new LowStockAlertDelivery(assignments, notifications, jdbc,
            Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC), items, transactions);
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).name("Synthetic store").active(true).build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().pharmacy(pharmacy).active(true)
            .quantityOnHand(BigDecimal.ONE).reorderThreshold(BigDecimal.TEN).build();
        item.setMedicationCatalogItem(com.example.hms.model.medication.MedicationCatalogItem.builder()
            .nameFr("Synthetic medicine").genericName("Synthetic medicine").build());
        item.setId(UUID.randomUUID());
        when(items.findForAlertDelivery(item.getId())).thenReturn(java.util.Optional.of(item));
        recipient = new User();
        recipient.setId(UUID.randomUUID());
        recipient.setUsername("synthetic-pharmacist");
    }

    @Test
    void recipientsComeFromTheItemsHospitalAndRepeatedRoleAssignmentsOnlyNotifyOnce() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(item.getPharmacy().getHospital().getId(),
            LowStockAlertDelivery.RECIPIENT_ROLES)).thenReturn(List.of(recipient, recipient));
        when(jdbc.update(anyString(), eq(item.getId()), eq(recipient.getId()), eq(DAY))).thenReturn(1);
        assertThat(service.deliver(item.getId())).isEqualTo(1);
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("inventory.lowStock.notification"), arguments.capture(),
            eq(recipient.getUsername()), eq("REORDER_ALERT"), eq(item.getId()));
        assertThat(arguments.getValue()).containsExactly(com.example.hms.service.i18n.NotificationLocalizer.drugName(item.getMedicationCatalogItem()), "Synthetic store", BigDecimal.ONE, BigDecimal.TEN);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void anExistingDailyClaimDoesNotSendAgain() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(recipient));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class), any(LocalDate.class))).thenReturn(0);
        assertThat(service.deliver(item.getId())).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void deliveryFailureRollsBackOnlyTheRecipientClaim() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(recipient));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class), any(LocalDate.class))).thenReturn(1);
        when(notifications.createNotification(anyString(), any(), anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("Synthetic storage failure"));
        assertThat(service.deliver(item.getId())).isZero();
        verify(transactions).rollback(any());
    }

    @Test
    void recoveredOrInactiveStockNeverRaisesAnAlert() {
        item.setQuantityOnHand(new BigDecimal("11"));
        assertThat(service.deliver(item.getId())).isZero();
        item.setQuantityOnHand(BigDecimal.ONE);
        item.getPharmacy().setActive(false);
        assertThat(service.deliver(item.getId())).isZero();
        verifyNoInteractions(assignments, jdbc, notifications);
    }
    @Test
    void deliveryUsesTheReloadedHospitalCatalogAndQuantitiesInsteadOfTheSweepSnapshot() {
        InventoryItem fresh = InventoryItem.builder().pharmacy(item.getPharmacy()).active(true)
            .medicationCatalogItem(item.getMedicationCatalogItem()).quantityOnHand(new BigDecimal("3"))
            .reorderThreshold(new BigDecimal("12")).build();
        fresh.setId(item.getId());
        when(items.findForAlertDelivery(item.getId())).thenReturn(java.util.Optional.of(fresh));
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(recipient));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class), any(LocalDate.class))).thenReturn(1);
        assertThat(service.deliver(item.getId())).isEqualTo(1);
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("inventory.lowStock.notification"), arguments.capture(),
            eq(recipient.getUsername()), eq("REORDER_ALERT"), eq(item.getId()));
        assertThat(arguments.getValue()).containsExactly(com.example.hms.service.i18n.NotificationLocalizer.drugName(item.getMedicationCatalogItem()), "Synthetic store", new BigDecimal("3"), new BigDecimal("12"));
    }

    @Test
    void drugNamesAreRenderedInTheRecipientsLanguageAtReadTime() {
        item.setMedicationCatalogItem(com.example.hms.model.medication.MedicationCatalogItem.builder()
            .nameFr("Nom fran\u00e7ais").genericName("English name").build());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(recipient));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class), any(LocalDate.class))).thenReturn(1);
        assertThat(service.deliver(item.getId())).isEqualTo(1);
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("inventory.lowStock.notification"), arguments.capture(), anyString(), anyString(), any());
        var localizer = new com.example.hms.service.i18n.NotificationLocalizer(com.example.hms.i18n.TestMessageSources.bundles());
        var stored = com.example.hms.service.i18n.NotificationLocalizer.storable(arguments.getValue());
        assertThat(localizer.render("inventory.lowStock.notification", stored, java.util.Locale.ENGLISH)).contains("English name");
        assertThat(localizer.render("inventory.lowStock.notification", stored, java.util.Locale.FRENCH)).contains("Nom fran\u00e7ais");
        assertThat(localizer.render("inventory.lowStock.notification", stored, java.util.Locale.forLanguageTag("es"))).contains("Nom fran\u00e7ais");
    }
}
