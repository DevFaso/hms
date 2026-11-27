package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.enums.ReturnedStockCondition;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.*;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.repository.pharmacy.*;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StockTransactionServiceImpl.class, StockTransferBooking.class, PharmacyPriceHistory.class, StockTransactionMapper.class,
    com.example.hms.security.EncryptionKeyHolder.class})
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class StockTransferPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_transfer").withUsername("hms_test_user").withPassword("hms_test_pass");
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
    @Autowired StockTransactionService service;
    @Autowired InventoryItemRepository items;
    @Autowired StockLotRepository lots;
    @Autowired StockTransactionRepository ledger;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;
    @MockitoBean RoleValidator roleValidator;
    @MockitoBean AuditEventLogService audit;
    @MockitoBean FefoLotPolicy fefo;
    @MockitoBean ControlledStockWitness witness;
    @MockitoBean StockQuarantine quarantine;
    private UUID hospitalId;
    private UUID sourceItemId;
    private UUID sourceLotId;
    private UUID destinationId;
    private UUID medicationId;
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO security.users (id, username, first_name, last_name, password_hash, email,
                    phone_number, is_active, is_deleted, force_password_change, created_at, updated_at)
                VALUES (:id, :username, 'Synthetic', 'Transfer', 'x', :email, :phone,
                    true, false, false, now(), now())
                """).setParameter("id", actorId).setParameter("username", "transfer-" + tag)
                .setParameter("email", tag + "@example.test").setParameter("phone", "+226" + tag).executeUpdate();
            Hospital hospital = new Hospital();
            hospital.setCode("TRANSFER-" + tag);
            hospital.setName("Synthetic transfer hospital");
            entityManager.persist(hospital);
            hospitalId = hospital.getId();
            Pharmacy source = Pharmacy.builder().hospital(hospital).name("Central").build();
            Pharmacy destination = Pharmacy.builder().hospital(hospital).name("Ward store").build();
            entityManager.persist(source);
            entityManager.persist(destination);
            destinationId = destination.getId();
            MedicationCatalogItem medication = MedicationCatalogItem.builder().nameFr("Synthetic medicine")
                .genericName("Synthetic medicine").form("Comprim?").strength("500 mg").build();
            entityManager.persist(medication);
            medicationId = medication.getId();
            InventoryItem item = InventoryItem.builder().pharmacy(source).medicationCatalogItem(medication)
                .unit("comprim?").unitPrice(new BigDecimal("500")).quantityOnHand(new BigDecimal("30")).build();
            entityManager.persist(item);
            sourceItemId = item.getId();
            StockLot lot = StockLot.builder().inventoryItem(item).lotNumber("D3943-" + tag)
                .expiryDate(LocalDate.now().plusYears(1)).initialQuantity(new BigDecimal("30"))
                .remainingQuantity(new BigDecimal("30")).unitCost(new BigDecimal("25"))
                .receivedDate(LocalDate.now()).receivedByUser(entityManager.find(User.class, actorId)).build();
            entityManager.persist(lot);
            sourceLotId = lot.getId();
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(actorId);
    }

    @Test
    void transferAndReturnKeepBothStoreTotalsAndTheirLotsBalanced() {
        StockTransactionResponseDTO transferred = service.recordTransaction(transfer("10"));
        InventoryItem destinationItem = items.findByPharmacyIdAndMedicationCatalogItemId(destinationId, medicationId).orElseThrow();
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(destinationItem.getQuantityOnHand()).isEqualByComparingTo("10");
        // QA D4181 (r81): reversed. The item a transfer creates is sold at its source's price; with none, a fill
        // from the destination was charged nothing.
        assertThat(destinationItem.getUnitPrice()).as("a new destination is sold at the source selling price")
            .isEqualByComparingTo("500");
        assertThat(items.findById(sourceItemId).orElseThrow().getUnitPrice()).isEqualByComparingTo("500");
        StockTransaction receipt = ledger.findByTransferOriginId(transferred.getId()).orElseThrow();
        StockLot receivedLot = lots.findById(receipt.getStockLot().getId()).orElseThrow();
        assertThat(receivedLot.getRemainingQuantity()).isEqualByComparingTo("10");
        assertThat(receivedLot.getExpiryDate()).isEqualTo(LocalDate.now().plusYears(1));
        assertThat(receivedLot.getUnitCost()).isEqualByComparingTo("25");
        assertThat(service.listReturnableTransfers(sourceItemId, sourceLotId)).singleElement().satisfies(transfer -> {
            assertThat(transfer.transferId()).isEqualTo(transferred.getId());
            assertThat(transfer.stockLotId()).isEqualTo(sourceLotId);
            assertThat(transfer.destinationName()).isEqualTo("Ward store");
            assertThat(transfer.returnableQuantity()).isEqualByComparingTo("10");
        });

        StockTransactionRequestDTO returned = StockTransactionRequestDTO.builder().inventoryItemId(sourceItemId)
            .stockLotId(sourceLotId).transactionType(StockTransactionType.RETURN).quantity(new BigDecimal("3"))
            .referenceId(transferred.getId()).reason("Unused ward stock returned")
            .stockCondition(ReturnedStockCondition.INTACT).build();
        service.recordTransaction(returned);
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("23");
        assertThat(items.findById(destinationItem.getId()).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("7");
        assertThat(lots.findById(sourceLotId).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("23");
        assertThat(lots.findById(receivedLot.getId()).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("7");
        assertThat(service.listReturnableTransfers(sourceItemId, sourceLotId)).singleElement()
            .satisfies(transfer -> assertThat(transfer.returnableQuantity()).isEqualByComparingTo("7"));
    }

    @Test
    void existingDestinationKeepsItsOwnPersistedSellingPrice() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            InventoryItem source = entityManager.find(InventoryItem.class, sourceItemId);
            entityManager.persist(InventoryItem.builder().pharmacy(entityManager.find(Pharmacy.class, destinationId))
                .medicationCatalogItem(source.getMedicationCatalogItem()).unit(source.getUnit())
                .unitPrice(new BigDecimal("375")).build());
        });
        service.recordTransaction(transfer("10"));
        assertThat(items.findByPharmacyIdAndMedicationCatalogItemId(destinationId, medicationId).orElseThrow().getUnitPrice())
            .isEqualByComparingTo("375");
    }

    @Test
    void duplicateGenericReceiptReferencesCannotRedirectOrBlockATransferReturn() {
        StockTransactionResponseDTO transferred = service.recordTransaction(transfer("10"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int index = 0; index < 2; index++) {
                entityManager.persist(StockTransaction.builder().inventoryItem(entityManager.find(InventoryItem.class, sourceItemId))
                    .transactionType(StockTransactionType.RECEIPT).quantity(BigDecimal.ONE).referenceId(transferred.getId())
                    .performedByUser(entityManager.find(User.class, actorId)).reason("Synthetic unrelated receipt").build());
            }
        });
        service.recordTransaction(StockTransactionRequestDTO.builder().inventoryItemId(sourceItemId).stockLotId(sourceLotId)
            .transactionType(StockTransactionType.RETURN).quantity(new BigDecimal("3")).referenceId(transferred.getId())
            .reason("Return unused stock").stockCondition(ReturnedStockCondition.INTACT).build());
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("23");
        assertThat(items.findByPharmacyIdAndMedicationCatalogItemId(destinationId, medicationId).orElseThrow().getQuantityOnHand())
            .isEqualByComparingTo("7");
        assertThat(ledger.findByTransferOriginId(transferred.getId())).isPresent();
    }

    @Test
    void returnChoicesAreCappedByTheReceiversCurrentStockAndRemainHospitalScoped() {
        var transferred = service.recordTransaction(transfer("10"));
        var receipt = ledger.findByTransferOriginId(transferred.getId()).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("UPDATE clinical.inventory_items SET quantity_on_hand = 4 WHERE id = :id")
                .setParameter("id", receipt.getInventoryItem().getId()).executeUpdate();
            entityManager.createNativeQuery("UPDATE clinical.stock_lots SET remaining_quantity = 4 WHERE id = :id")
                .setParameter("id", receipt.getStockLot().getId()).executeUpdate();
        });
        assertThat(service.listReturnableTransfers(sourceItemId, null)).singleElement()
            .satisfies(transfer -> assertThat(transfer.returnableQuantity()).isEqualByComparingTo("4"));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.listReturnableTransfers(sourceItemId, sourceLotId))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    void sourceRefusalRollsBackTheDestinationItemLotAndReceipt() {
        long before = ledger.count();
        assertThatThrownBy(() -> service.recordTransaction(transfer("31")))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo("stock.lot.insufficient");
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("30");
        assertThat(lots.findById(sourceLotId).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("30");
        assertThat(items.findByPharmacyIdAndMedicationCatalogItemId(destinationId, medicationId)).isEmpty();
        assertThat(ledger.count()).isEqualTo(before);
    }

    @Test
    void aReturnCannotTakeBackStockAlreadyUsedAtTheDestinationAndRollsBackBothLedgers() {
        StockTransactionResponseDTO transferred = service.recordTransaction(transfer("10"));
        StockTransaction receipt = ledger.findByTransferOriginId(transferred.getId()).orElseThrow();
        UUID receivingItemId = receipt.getInventoryItem().getId();
        UUID receivingLotId = receipt.getStockLot().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var now = java.time.LocalDateTime.now();
            assertThat(lots.decrementRemaining(receivingLotId, new BigDecimal("9"), now)).isEqualTo(1);
            assertThat(items.decrementOnHand(receivingItemId, new BigDecimal("9"), now)).isEqualTo(1);
        });
        long before = ledger.count();
        var returned = StockTransactionRequestDTO.builder().inventoryItemId(sourceItemId).stockLotId(sourceLotId)
            .transactionType(StockTransactionType.RETURN).quantity(new BigDecimal("3"))
            .referenceId(transferred.getId()).reason("Synthetic return after destination use")
            .stockCondition(ReturnedStockCondition.INTACT).build();
        assertThatThrownBy(() -> service.recordTransaction(returned))
            .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.transfer.destinationInsufficient");
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(lots.findById(sourceLotId).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("20");
        assertThat(items.findById(receivingItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("1");
        assertThat(lots.findById(receivingLotId).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("1");
        assertThat(ledger.count()).isEqualTo(before);
    }

    @Test
    void introducingALotIntoExistingLotlessDestinationStockIsRefusedAtomically() {
        UUID receiver = new TransactionTemplate(transactionManager).execute(status -> {
            InventoryItem item = InventoryItem.builder()
                .pharmacy(entityManager.find(Pharmacy.class, destinationId))
                .medicationCatalogItem(entityManager.find(MedicationCatalogItem.class, medicationId))
                .unit("comprim?").quantityOnHand(new BigDecimal("20")).build();
            entityManager.persist(item);
            return item.getId();
        });
        long before = ledger.count();
        assertThatThrownBy(() -> service.recordTransaction(transfer("5")))
            .hasFieldOrPropertyWithValue("messageKey", "pharmacy.inventory.lot.balanceUnreconciled");
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("30");
        assertThat(items.findById(receiver).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(lots.sumRemainingForItem(receiver)).isEqualByComparingTo("0");
        assertThat(ledger.count()).isEqualTo(before);
    }

    @Test
    void anOldLotlessTransferCannotDebitTheBalanceOfANewLot() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> lots.deleteById(sourceLotId));
        sourceLotId = null;
        StockTransactionResponseDTO moved = service.recordTransaction(transfer("10"));
        StockTransaction receipt = ledger.findByTransferOriginId(moved.getId()).orElseThrow();
        UUID receiver = receipt.getInventoryItem().getId();
        UUID laterLot = new TransactionTemplate(transactionManager).execute(status -> {
            var now = java.time.LocalDateTime.now();
            assertThat(items.decrementOnHand(receiver, BigDecimal.TEN, now)).isEqualTo(1);
            StockLot lot = StockLot.builder().inventoryItem(entityManager.find(InventoryItem.class, receiver))
                .lotNumber("LATER-" + UUID.randomUUID()).expiryDate(LocalDate.now().plusYears(1))
                .initialQuantity(BigDecimal.TEN).remainingQuantity(BigDecimal.TEN).unitCost(BigDecimal.ONE)
                .receivedDate(LocalDate.now()).receivedByUser(entityManager.find(User.class, actorId)).build();
            entityManager.persist(lot);
            assertThat(items.incrementOnHand(receiver, BigDecimal.TEN, now)).isEqualTo(1);
            return lot.getId();
        });
        long before = ledger.count();
        var returned = StockTransactionRequestDTO.builder().inventoryItemId(sourceItemId)
            .transactionType(StockTransactionType.RETURN).quantity(new BigDecimal("5")).referenceId(moved.getId())
            .reason("Synthetic return after lot tracking changed").stockCondition(ReturnedStockCondition.INTACT).build();
        assertThatThrownBy(() -> service.recordTransaction(returned))
            .hasFieldOrPropertyWithValue("messageKey", "pharmacy.inventory.lot.trackingChanged");
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(items.findById(receiver).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("10");
        assertThat(lots.findById(laterLot).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("10");
        assertThat(ledger.count()).isEqualTo(before);
    }

    @Test
    void anUnreferencedReturnCannotDuplicateAPairedTransfer() {
        service.recordTransaction(transfer("10"));
        assertThat(ledger.hasPairedTransfer(sourceItemId, false, null)).isTrue();
        long before = ledger.count();
        var returned = StockTransactionRequestDTO.builder().inventoryItemId(sourceItemId).stockLotId(sourceLotId)
            .transactionType(StockTransactionType.RETURN).quantity(BigDecimal.TEN)
            .reason("Synthetic unreferenced return").stockCondition(ReturnedStockCondition.INTACT).build();
        assertThatThrownBy(() -> service.recordTransaction(returned))
            .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.transfer.referenceRequired");
        assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("20");
        assertThat(ledger.count()).isEqualTo(before);
        var destination = items.findByPharmacyIdAndMedicationCatalogItemId(destinationId, medicationId).orElseThrow();
        assertThat(destination.getQuantityOnHand()).isEqualByComparingTo("10");
    }

    @Test
    void aBusyLotRefusesTheTransferPromptlyAndLeavesNoDestinationCredit() throws Exception {
        long before = ledger.count();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM clinical.stock_lots WHERE id = :id FOR UPDATE")
                    .setParameter("id", sourceLotId).getSingleResult();
                locked.countDown();
                try {
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var attempt = executor.submit(() -> service.recordTransaction(transfer("1")));
                var refused = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> attempt.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThat(refused.getCause()).isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.transfer.stockBusy");
                assertThat(items.findById(sourceItemId).orElseThrow().getQuantityOnHand()).isEqualByComparingTo("30");
                assertThat(items.findByPharmacyIdAndMedicationCatalogItemId(destinationId, medicationId)).isEmpty();
                assertThat(ledger.count()).isEqualTo(before);
            } finally {
                release.countDown();
                blocker.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    private StockTransactionRequestDTO transfer(String quantity) {
        return StockTransactionRequestDTO.builder().inventoryItemId(sourceItemId).stockLotId(sourceLotId)
            .transactionType(StockTransactionType.TRANSFER).quantity(new BigDecimal(quantity))
            .referenceId(destinationId).reason("Ward replenishment").build();
    }
}
