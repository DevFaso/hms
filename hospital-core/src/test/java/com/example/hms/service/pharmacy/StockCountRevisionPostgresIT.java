package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.*;
import com.example.hms.payload.dto.pharmacy.*;
import com.example.hms.repository.pharmacy.*;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual migration, ORM, count/adjustment service and delivery transactions; synthetic data, no external sender. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StockCountService.class, StockBalanceReconciliationService.class, StockTransactionServiceImpl.class, StockTransferBooking.class, PharmacyPriceHistory.class, StockTransactionMapper.class,
    LowStockAlertDelivery.class, StockCountRevisionPostgresIT.TestClock.class,
    com.example.hms.security.EncryptionKeyHolder.class})
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class StockCountRevisionPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_count_revisions").withUsername("hms_test_user").withPassword("hms_test_pass");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean Clock stockCountClock() { return Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired StockCountService counts;
    @Autowired StockBalanceReconciliationService reconciliation;
    @Autowired LowStockAlertDelivery delivery;
    @Autowired InventoryItemRepository items;
    @Autowired StockLotRepository lots;
    @Autowired StockTransactionRepository ledger;
    @Autowired StockCountRepository observations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @PersistenceContext EntityManager entityManager;
    @MockitoBean RoleValidator roles;
    @MockitoBean AuditEventLogService audit;
    @MockitoBean FefoLotPolicy fefo;
    @MockitoBean ControlledStockWitness witness;
    @MockitoBean StockQuarantine quarantine;
    @MockitoBean NotificationService notifications;
    @MockitoBean UserRoleHospitalAssignmentRepository assignments;
    private UUID hospitalId, itemId, actorId;
    private TransactionTemplate transaction;

    @BeforeEach
    void seed() {
        transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            actorId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO security.users (id, username, first_name, last_name, password_hash, email,
                    phone_number, is_active, is_deleted, force_password_change, created_at, updated_at)
                VALUES (?, ?, 'Synthetic', 'Counter', 'x', ?, ?, true, false, false, now(), now())
                """, actorId, "counter-" + tag, tag + "@example.test", "+226" + tag);
            Hospital hospital = new Hospital();
            hospital.setCode("COUNT-" + tag); hospital.setName("Synthetic count hospital");
            entityManager.persist(hospital); hospitalId = hospital.getId();
            Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).name("Synthetic store").active(true).build();
            entityManager.persist(pharmacy);
            MedicationCatalogItem medicine = MedicationCatalogItem.builder().nameFr("Synthetic medicine")
                .genericName("Synthetic medicine").code(tag).build();
            entityManager.persist(medicine);
            InventoryItem item = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(medicine)
                .quantityOnHand(new BigDecimal("20")).unit("tablet").active(true).reorderThreshold(BigDecimal.TEN).build();
            entityManager.persist(item); entityManager.flush(); itemId = item.getId();
        });
        when(roles.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roles.getCurrentUserId()).thenReturn(actorId);
        when(roles.hasRole(eq(actorId), eq(hospitalId), anyString()))
            .thenAnswer(call -> "PHARMACIST".equals(call.getArgument(2)));
    }

    private StockCountRequestDTO start(UUID lotId) {
        InventoryItem item = items.findById(itemId).orElseThrow();
        StockLot lot = lotId == null ? null : lots.findById(lotId).orElseThrow();
        return new StockCountRequestDTO(itemId, lotId, UUID.randomUUID(),
            lot == null ? item.getQuantityOnHand() : lot.getRemainingQuantity(), item.getUnit(),
            item.getStockRevision(), lot == null ? null : lot.getStockRevision(),
            new BigDecimal("18"), "Synthetic witnessed recount");
    }
    private void itemAba() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 19 WHERE id = ?", itemId);
            jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 20 WHERE id = ?", itemId);
        });
    }

    @Test
    void nativeMovementReturningToTheSameQuantityRequiresARecountBeforeRecording() {
        var request = start(null); itemAba();
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(items.findById(itemId).orElseThrow().getStockRevision()).isEqualTo(2L);
        assertThatThrownBy(() -> counts.record(request)).hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        assertThat(observations.findByHospitalIdAndClientRequestId(hospitalId, request.clientRequestId())).isEmpty();
    }

    @Test
    void nativeMovementReturningToTheRecordedQuantityAlsoRefusesConfirmation() {
        var recorded = counts.record(start(null)); itemAba();
        assertThatThrownBy(() -> counts.confirm(recorded.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        assertThat(observations.findById(recorded.getId()).orElseThrow().getAppliedAt()).isNull();
        assertThat(ledger.findByInventoryItemId(itemId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isZero();
    }

    @Test
    void ormUnitChangeAndBackCannotResetTheGeneratedRevision() {
        var request = start(null);
        transaction.executeWithoutResult(status -> {
            var item = items.findById(itemId).orElseThrow(); item.setUnit("box"); items.saveAndFlush(item);
            assertThat(item.getStockRevision()).isEqualTo(1L);
            item.setUnit("tablet"); items.saveAndFlush(item); assertThat(item.getStockRevision()).isEqualTo(2L);
        });
        assertThatThrownBy(() -> counts.record(request)).hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
    }

    @Test
    void aLotMovementAndBackIsRememberedEvenWhenTheItemTotalNeverChanges() {
        UUID lotId = transaction.execute(status -> {
            var lot = StockLot.builder().inventoryItem(items.findById(itemId).orElseThrow()).lotNumber("COUNT-LOT")
                .expiryDate(LocalDate.now().plusYears(1)).initialQuantity(new BigDecimal("20"))
                .remainingQuantity(new BigDecimal("20")).receivedDate(LocalDate.now())
                .receivedByUser(entityManager.find(User.class, actorId)).unitCost(BigDecimal.ONE).build();
            return lots.saveAndFlush(lot).getId();
        });
        var recorded = counts.record(start(lotId));
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE clinical.stock_lots SET remaining_quantity = 19 WHERE id = ?", lotId);
            jdbc.update("UPDATE clinical.stock_lots SET remaining_quantity = 20 WHERE id = ?", lotId);
        });
        assertThat(lots.findById(lotId).orElseThrow().getStockRevision()).isEqualTo(2L);
        assertThatThrownBy(() -> counts.confirm(recorded.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
    }

    @Test
    void explicitConfirmationAppliesTheObservedVarianceOnlyOnceThroughTheRealAdjustment() {
        var recorded = counts.record(start(null));
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        var first = counts.confirm(recorded.getId(), new StockCountConfirmationDTO(true, null));
        assertThat(first.getAdjustmentId()).isNotNull();
        assertThat(counts.confirm(recorded.getId(), new StockCountConfirmationDTO(true, null)).getAdjustmentId()).isEqualTo(first.getAdjustmentId());
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("18");
        assertThat(ledger.findByInventoryItemId(itemId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(1);
    }

    @Test
    void historicalCountsWithoutAnObservedRevisionMustBeRecounted() {
        var recorded = counts.record(start(null));
        jdbc.update("UPDATE clinical.pharmacy_stock_counts SET book_item_revision = NULL WHERE id = ?", recorded.getId());
        assertThatThrownBy(() -> counts.confirm(recorded.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
    }

    @Test
    void deliveryReloadsCommittedStockInItsOwnTransactionAndDoesNotSpendAClaimOnRecoveredStock() {
        User recipient = new User(); recipient.setId(actorId); recipient.setUsername("synthetic-recipient");
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, LowStockAlertDelivery.RECIPIENT_ROLES))
            .thenReturn(List.of(recipient));
        jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 1 WHERE id = ?", itemId);
        transaction.executeWithoutResult(status -> {
            InventoryItem stale = items.findById(itemId).orElseThrow();
            var independent = new TransactionTemplate(manager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(ignored -> jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 50 WHERE id = ?", itemId));
            assertThat(stale.getQuantityOnHand()).isEqualByComparingTo("1");
            assertThat(delivery.deliver(itemId)).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical.low_stock_alert_receipts WHERE inventory_item_id = ?", Integer.class, itemId)).isZero();
        verifyNoInteractions(notifications);
        jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 3 WHERE id = ?", itemId);
        assertThat(delivery.deliver(itemId)).isEqualTo(1);
        assertThat(delivery.deliver(itemId)).isZero();
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("inventory.lowStock.notification"), args.capture(),
            eq("synthetic-recipient"), eq("REORDER_ALERT"), eq(itemId));
        assertThat(args.getValue()).containsExactly(java.util.Map.of("drugNameEn", "Synthetic medicine", "drugNameDisplay", "Synthetic medicine"), "Synthetic store", new BigDecimal("3.00"), new BigDecimal("10.00"));
    }
    @Test
    void aStaleRecoveredSnapshotCannotHideANewShortage() {
        User recipient = new User(); recipient.setId(actorId); recipient.setUsername("synthetic-recipient");
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, LowStockAlertDelivery.RECIPIENT_ROLES))
            .thenReturn(List.of(recipient));
        jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 50 WHERE id = ?", itemId);
        transaction.executeWithoutResult(status -> {
            InventoryItem stale = items.findById(itemId).orElseThrow();
            var independent = new TransactionTemplate(manager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(ignored -> jdbc.update("UPDATE clinical.inventory_items SET quantity_on_hand = 3 WHERE id = ?", itemId));
            assertThat(stale.getQuantityOnHand()).isEqualByComparingTo("50");
            assertThat(delivery.deliver(itemId)).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical.low_stock_alert_receipts WHERE inventory_item_id = ?", Integer.class, itemId)).isEqualTo(1);
        verify(notifications).createNotification(eq("inventory.lowStock.notification"), any(), eq("synthetic-recipient"), eq("REORDER_ALERT"), eq(itemId));
    }

    @Test
    void physicallyVerifiedLotBalancesRepairOnlyTheAggregateAndRecordAnAdjustment() {
        UUID lotId = transaction.execute(status -> {
            var lot = StockLot.builder().inventoryItem(items.findById(itemId).orElseThrow()).lotNumber("COUNT-REPAIR")
                .expiryDate(LocalDate.now().plusYears(1)).initialQuantity(new BigDecimal("15"))
                .remainingQuantity(new BigDecimal("15")).receivedDate(LocalDate.now())
                .receivedByUser(entityManager.find(User.class, actorId)).unitCost(BigDecimal.ONE).build();
            return lots.saveAndFlush(lot).getId();
        });
        Long revision = items.findById(itemId).orElseThrow().getStockRevision();
        var request = new StockBalanceReconciliationRequest(new BigDecimal("20"), new BigDecimal("15"), revision,
            true, "Physical count verified", null,
            List.of(new StockBalanceReconciliationRequest.LotCount(lotId, new BigDecimal("15"))));
        UUID movementId = reconciliation.reconcile(itemId, request);
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("15");
        assertThat(lots.findById(lotId).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("15");
        var movement = ledger.findById(movementId).orElseThrow();
        assertThat(movement.getQuantity()).isEqualByComparingTo("-5");
        assertThat(movement.getStockLot()).isNull();
        assertThat(movement.getReason()).contains("Physical count verified");
        assertThat(jdbc.queryForObject("SELECT counted_quantity FROM clinical.stock_balance_reconciliation_lot_counts WHERE movement_id = ? AND stock_lot_id = ?", BigDecimal.class, movementId, lotId)).isEqualByComparingTo("15");
        assertThatThrownBy(() -> reconciliation.reconcile(itemId, request))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        assertThat(ledger.findByInventoryItemId(itemId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(1);
    }

    @Test
    void reconciliationRefusesALotBeingChangedAndLeavesNoAdjustment() throws Exception {
        UUID lotId = transaction.execute(status -> {
            var lot = StockLot.builder().inventoryItem(items.findById(itemId).orElseThrow()).lotNumber("COUNT-REPAIR-BUSY")
                .expiryDate(LocalDate.now().plusYears(1)).initialQuantity(new BigDecimal("15"))
                .remainingQuantity(new BigDecimal("15")).receivedDate(LocalDate.now())
                .receivedByUser(entityManager.find(User.class, actorId)).unitCost(BigDecimal.ONE).build();
            return lots.saveAndFlush(lot).getId();
        });
        var request = new StockBalanceReconciliationRequest(new BigDecimal("20"), new BigDecimal("15"),
            items.findById(itemId).orElseThrow().getStockRevision(), true, "Physical count verified", null,
            List.of(new StockBalanceReconciliationRequest.LotCount(lotId, new BigDecimal("15"))));
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM clinical.stock_lots WHERE id = ? FOR UPDATE", UUID.class, lotId);
                held.countDown();
                try { if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Lock test timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reconciliation.reconcile(itemId, request))
                    .hasFieldOrPropertyWithValue("messageKey", "stockCount.stockBusy");
            } finally { release.countDown(); }
            holder.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(lots.findById(lotId).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("15");
        assertThat(ledger.findByInventoryItemId(itemId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isZero();
    }

    @Test
    void consumptionQueryExcludesTransferReturnsFromQuantitiesAndSourceMovementCount() {
        UUID origin = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO clinical.stock_transactions (id, inventory_item_id, transaction_type, quantity, performed_by, unit_snapshot, created_at, updated_at) VALUES (?, ?, 'TRANSFER', 3, ?, 'tablet', now(), now())", origin, itemId, actorId);
            jdbc.update("INSERT INTO clinical.stock_transactions (id, inventory_item_id, transaction_type, quantity, performed_by, unit_snapshot, created_at, updated_at) VALUES (?, ?, 'SALE', 2, ?, 'tablet', now(), now())", UUID.randomUUID(), itemId, actorId);
            jdbc.update("INSERT INTO clinical.stock_transactions (id, inventory_item_id, transaction_type, quantity, performed_by, unit_snapshot, reference_id, created_at, updated_at) VALUES (?, ?, 'RETURN', 1, ?, 'tablet', ?, now(), now())", UUID.randomUUID(), itemId, actorId, origin);
            jdbc.update("INSERT INTO clinical.stock_transactions (id, inventory_item_id, transaction_type, quantity, performed_by, unit_snapshot, created_at, updated_at) VALUES (?, ?, 'RETURN', 4, ?, 'tablet', now(), now())", UUID.randomUUID(), itemId, actorId);
        });
        UUID pharmacy = transaction.execute(status -> items.findById(itemId).orElseThrow().getPharmacy().getId());
        var rows = ledger.consumptionByRecordedUnit(pharmacy, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
            Set.of(com.example.hms.enums.StockTransactionType.SALE, com.example.hms.enums.StockTransactionType.DISPENSE),
            com.example.hms.enums.StockTransactionType.RETURN, com.example.hms.enums.StockTransactionType.TRANSFER);
        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.getFirst()[3].toString())).isEqualByComparingTo("2");
        assertThat(new BigDecimal(rows.getFirst()[4].toString())).isZero();
        assertThat(((Number) rows.getFirst()[5]).longValue()).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsPersistOneAdjustmentAndOneAppliedVariance() throws Exception {
        var recorded = counts.record(start(null));
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<StockCountResponseDTO> confirm = () -> {
                if (!go.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return counts.confirm(recorded.getId(), new StockCountConfirmationDTO(true, null));
            };
            var first = pool.submit(confirm); var second = pool.submit(confirm); go.countDown();
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS).getAdjustmentId())
                .isEqualTo(second.get(30, java.util.concurrent.TimeUnit.SECONDS).getAdjustmentId());
        }
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("18");
        assertThat(ledger.findByInventoryItemId(itemId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(1);
        var persisted = observations.findById(recorded.getId()).orElseThrow();
        assertThat(persisted.getAppliedAt()).isNotNull();
        assertThat(persisted.getVariance()).isEqualByComparingTo("-2");
    }

    @Test
    void concurrentRecordRetriesPersistOneObservationWithoutAnyLedgerMovement() throws Exception {
        var request = start(null);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<UUID> record = () -> {
                if (!go.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try { return counts.record(request).getId(); }
                catch (com.example.hms.exception.BusinessException busy) {
                    if (!"stockCount.stockBusy".equals(busy.getMessageKey())) throw busy;
                    return null; // NOWAIT refuses a simultaneous lock; an explicit retry follows both transactions.
                }
            };
            var first = pool.submit(record); var second = pool.submit(record); go.countDown();
            UUID one = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            UUID two = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
            UUID retry = counts.record(request).getId();
            assertThat(one != null ? one : two).isEqualTo(retry);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical.pharmacy_stock_counts WHERE hospital_id = ? AND client_request_id = ?", Integer.class,
            hospitalId, request.clientRequestId())).isEqualTo(1);
        assertThat(items.findById(itemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(ledger.findByInventoryItemId(itemId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isZero();
    }
}
