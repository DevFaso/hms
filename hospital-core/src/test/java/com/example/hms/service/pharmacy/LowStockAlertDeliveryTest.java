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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LowStockAlertDeliveryTest {
    @Mock private UserRoleHospitalAssignmentRepository assignments;
    @Mock private NotificationService notifications;
    @Mock private JdbcTemplate jdbc;
    private LowStockAlertDelivery service;
    private InventoryItem item;
    private User recipient;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

    @BeforeEach
    void setUp() {
        service = new LowStockAlertDelivery(assignments, notifications, jdbc,
            Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC));
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).active(true).build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().pharmacy(pharmacy).active(true)
            .quantityOnHand(BigDecimal.ONE).reorderThreshold(BigDecimal.TEN).build();
        item.setId(UUID.randomUUID());
        recipient = new User();
        recipient.setId(UUID.randomUUID());
        recipient.setUsername("synthetic-pharmacist");
    }

    @Test
    void recipientsComeFromTheItemsHospitalAndRepeatedRoleAssignmentsOnlyNotifyOnce() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(item.getPharmacy().getHospital().getId(),
            LowStockAlertDelivery.RECIPIENT_ROLES)).thenReturn(List.of(recipient, recipient));
        when(jdbc.update(anyString(), eq(item.getId()), eq(recipient.getId()), eq(DAY))).thenReturn(1);
        Object[] arguments = {"Synthetic medicine", "Synthetic store", 1, 10};
        assertThat(service.deliver(item, arguments)).isEqualTo(1);
        verify(notifications).createNotification("inventory.lowStock.notification", arguments,
            recipient.getUsername(), "REORDER_ALERT", item.getId());
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void anExistingDailyClaimDoesNotSendAgain() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(recipient));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class), any(LocalDate.class))).thenReturn(0);
        assertThat(service.deliver(item, new Object[0])).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void deliveryFailurePropagatesSoTheTransactionCanRollBackItsClaim() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(recipient));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class), any(LocalDate.class))).thenReturn(1);
        when(notifications.createNotification(anyString(), any(), anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("Synthetic storage failure"));
        assertThatThrownBy(() -> service.deliver(item, new Object[0])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recoveredOrInactiveStockNeverRaisesAnAlert() {
        item.setQuantityOnHand(new BigDecimal("11"));
        assertThat(service.deliver(item, new Object[0])).isZero();
        item.setQuantityOnHand(BigDecimal.ONE);
        item.getPharmacy().setActive(false);
        assertThat(service.deliver(item, new Object[0])).isZero();
        verifyNoInteractions(assignments, jdbc, notifications);
    }
}
