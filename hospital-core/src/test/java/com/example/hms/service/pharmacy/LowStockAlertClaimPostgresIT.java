package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.Notification;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NotificationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL contention/rollback, synthetic colleagues, in-app sender mocked; never an external gateway. */
@Testcontainers
class LowStockAlertClaimPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_low_stock_claims").withUsername("hms_test_user").withPassword("hms_test_pass");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private NotificationService notifications;
    private LowStockAlertDelivery delivery;
    private InventoryItem item;

    @BeforeAll
    static void schema() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE SCHEMA clinical; CREATE SCHEMA security");
        jdbc.execute("CREATE TABLE clinical.inventory_items (id uuid PRIMARY KEY)");
        jdbc.execute("CREATE TABLE security.users (id uuid PRIMARY KEY)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V396__pharmacy_stock_controls.sql")));
    }

    @BeforeEach
    void fixture() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).active(true).build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().pharmacy(pharmacy).quantityOnHand(BigDecimal.ONE)
            .reorderThreshold(BigDecimal.TEN).active(true).build();
        item.setId(UUID.randomUUID());
        User pharmacist = new User();
        pharmacist.setId(UUID.randomUUID());
        pharmacist.setUsername("synthetic-pharmacist");
        jdbc.update("INSERT INTO clinical.inventory_items VALUES (?)", item.getId());
        jdbc.update("INSERT INTO security.users VALUES (?)", pharmacist.getId());
        var assignments = mock(UserRoleHospitalAssignmentRepository.class);
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospital.getId(), LowStockAlertDelivery.RECIPIENT_ROLES))
            .thenReturn(List.of(pharmacist));
        notifications = mock(NotificationService.class);
        delivery = new LowStockAlertDelivery(assignments, notifications, jdbc,
            Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void concurrentManualAndScheduledSweepsCommitOnlyOneNotification() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return transaction.execute(status -> delivery.deliver(item, new Object[0]));
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return transaction.execute(status -> delivery.deliver(item, new Object[0]));
            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(claimCount()).isEqualTo(1);
        verify(notifications, times(1)).createNotification(anyString(), any(), anyString(), anyString(), eq(item.getId()));
    }

    @Test
    void aFailedNotificationRollsBackTheClaimAndASuccessfulRetryCannotSendTwice() {
        when(notifications.createNotification(anyString(), any(), anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("Synthetic notification failure")).thenReturn(new Notification());
        assertThatThrownBy(() -> transaction.execute(status -> delivery.deliver(item, new Object[0])))
            .isInstanceOf(IllegalStateException.class);
        assertThat(claimCount()).isZero();
        assertThat(transaction.execute(status -> delivery.deliver(item, new Object[0]))).isEqualTo(1);
        assertThat(transaction.execute(status -> delivery.deliver(item, new Object[0]))).isZero();
        assertThat(claimCount()).isEqualTo(1);
        verify(notifications, times(2)).createNotification(anyString(), any(), anyString(), anyString(), eq(item.getId()));
    }

    private int claimCount() {
        return jdbc.queryForObject("SELECT count(*) FROM clinical.low_stock_alert_receipts WHERE inventory_item_id = ?",
            Integer.class, item.getId());
    }
}
