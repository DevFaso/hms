package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockTransactionServiceImplTest {

    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private UserRepository userRepository;
    @Mock private StockTransactionMapper stockTransactionMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private com.example.hms.repository.pharmacy.DispenseRepository dispenseRepository;

    @InjectMocks
    private StockTransactionServiceImpl service;

    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID inventoryItemId = UUID.randomUUID();
    private final UUID stockLotId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Hospital hospital;
    private Pharmacy pharmacy;
    private InventoryItem inventoryItem;
    private StockLot stockLot;
    private User user;

    @BeforeEach
    void setUp() {
        // MessageUtil is static: without its own source this class only passed when an
        // earlier test had installed one, and asserted on raw keys when run alone.
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");

        pharmacy = Pharmacy.builder()
                .hospital(hospital)
                .name("Main Pharmacy")
                .build();
        pharmacy.setId(pharmacyId);

        MedicationCatalogItem medication = new MedicationCatalogItem();
        medication.setId(UUID.randomUUID());

        inventoryItem = InventoryItem.builder()
                .pharmacy(pharmacy)
                .medicationCatalogItem(medication)
                .quantityOnHand(new BigDecimal("100.00"))
                .reorderThreshold(new BigDecimal("10.00"))
                .reorderQuantity(new BigDecimal("50.00"))
                .unit("tablets")
                .active(true)
                .build();
        inventoryItem.setId(inventoryItemId);

        stockLot = StockLot.builder()
                .inventoryItem(inventoryItem)
                .lotNumber("LOT-001")
                .expiryDate(LocalDate.now().plusYears(1))
                .initialQuantity(new BigDecimal("50.00"))
                .remainingQuantity(new BigDecimal("50.00"))
                .supplier("PharmaCo")
                .receivedDate(LocalDate.now())
                .build();
        stockLot.setId(stockLotId);

        user = new User();
        user.setId(userId);
        user.setUsername("pharmacist1");
        stockMovesLikeTheDatabase();
    }

    /**
     * The atomic quantity UPDATEs (#825 round 5) act on the fixture as the
     * database would: a decrement applies only when enough is left.
     */
    private void stockMovesLikeTheDatabase() {
        org.mockito.Mockito.lenient().when(stockLotRepository.decrementRemaining(any(), any(), any()))
                .thenAnswer(inv -> {
                    BigDecimal q = inv.getArgument(1);
                    if (stockLot.getRemainingQuantity().compareTo(q) < 0) return 0;
                    stockLot.setRemainingQuantity(stockLot.getRemainingQuantity().subtract(q));
                    return 1;
                });
        org.mockito.Mockito.lenient().when(stockLotRepository.incrementRemaining(any(), any(), any()))
                .thenAnswer(inv -> {
                    stockLot.setRemainingQuantity(stockLot.getRemainingQuantity().add(inv.getArgument(1)));
                    return 1;
                });
        // QA D405: a positive adjustment stays within what the lot received.
        org.mockito.Mockito.lenient().when(stockLotRepository.incrementRemainingWithinReceived(any(), any(), any()))
                .thenAnswer(inv -> {
                    BigDecimal after = stockLot.getRemainingQuantity().add(inv.getArgument(1));
                    if (after.compareTo(stockLot.getInitialQuantity()) > 0) return 0;
                    stockLot.setRemainingQuantity(after);
                    return 1;
                });
        org.mockito.Mockito.lenient().when(inventoryItemRepository.decrementOnHand(any(), any(), any()))
                .thenAnswer(inv -> {
                    BigDecimal q = inv.getArgument(1);
                    if (inventoryItem.getQuantityOnHand().compareTo(q) < 0) return 0;
                    inventoryItem.setQuantityOnHand(inventoryItem.getQuantityOnHand().subtract(q));
                    return 1;
                });
        org.mockito.Mockito.lenient().when(inventoryItemRepository.incrementOnHand(any(), any(), any()))
                .thenAnswer(inv -> {
                    inventoryItem.setQuantityOnHand(inventoryItem.getQuantityOnHand().add(inv.getArgument(1)));
                    return 1;
                });
    }

    private void stubHospitalScope() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private StockTransactionRequestDTO buildRequest(StockTransactionType type, BigDecimal quantity) {
        return StockTransactionRequestDTO.builder()
                .inventoryItemId(inventoryItemId)
                .stockLotId(stockLotId)
                .transactionType(type)
                .quantity(quantity)
                .reason("Test reason")
                .performedBy(userId)
                .build();
    }

    private StockTransactionResponseDTO buildResponse() {
        return StockTransactionResponseDTO.builder()
                .id(transactionId)
                .inventoryItemId(inventoryItemId)
                .stockLotId(stockLotId)
                .transactionType("RECEIPT")
                .quantity(new BigDecimal("20.00"))
                .build();
    }

    private void stubCommonMocks(StockTransactionRequestDTO dto, boolean withLot) {
        when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
        stubHospitalScope();
        if (withLot) {
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
        }
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StockTransaction tx = StockTransaction.builder()
                .inventoryItem(inventoryItem)
                .stockLot(withLot ? stockLot : null)
                .transactionType(dto.getTransactionType())
                .quantity(dto.getQuantity())
                .reason(dto.getReason())
                .performedByUser(user)
                .build();
        tx.setId(transactionId);
        when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), any(), eq(user))).thenReturn(tx);
        when(stockTransactionRepository.save(tx)).thenReturn(tx);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(buildResponse());
    }

    // ── recordTransaction — RECEIPT ──────────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — RECEIPT (QA D615)")
    class RecordReceipt {

        private void makeControlledWithoutLots() {
            inventoryItem.getMedicationCatalogItem().setControlled(true);
        }

        /**
         * QA D615: POST /pharmacy/stock-transactions RECEIPT on a controlled
         * item with no lot yet — a normal one, the same lot number with
         * another expiry, and an expired lot — all returned 201 with no lot
         * row and no witness. A receipt goes through the lot receipt.
         */
        @Test
        @DisplayName("D615: every API receipt is refused and moves nothing — normal, duplicate lot, expired lot")
        void receiptIsRefusedOnThisEndpoint() {
            makeControlledWithoutLots();
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            List<StockTransactionRequestDTO> attempts = List.of(
                    receipt("LOT-QA-1", LocalDate.now().plusYears(1)),
                    receipt("LOT-QA-1", LocalDate.now().plusYears(2)),
                    receipt("LOT-QA-OLD", LocalDate.now().minusDays(10)));
            for (StockTransactionRequestDTO dto : attempts) {
                assertThatThrownBy(() -> service.recordTransaction(dto))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(MessageUtil.resolve("stockTransaction.receipt.viaLots"));
            }
            verify(stockTransactionRepository, never()).save(any());
            verify(inventoryItemRepository, never()).incrementOnHand(any(), any(), any());
            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        private StockTransactionRequestDTO receipt(String lotNumber, LocalDate expiry) {
            return StockTransactionRequestDTO.builder()
                    .inventoryItemId(inventoryItemId)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(new BigDecimal("30"))
                    .lotNumber(lotNumber)
                    .expiryDate(expiry)
                    .build();
        }
    }

    // ── recordTransaction — DISPENSE ─────────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — DISPENSE")
    class RecordDispense {

        @Test
        @DisplayName("D82: a dispense is refused here; it goes through the prescription workflow")
        void dispenseIsRefused() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.DISPENSE, new BigDecimal("5.00"));
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(stockTransactionRepository, never()).save(any());
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    // ── D82: accountability of a manual movement ─────────────────────────

    @Nested
    @DisplayName("recordTransaction — accountability (D82)")
    class Accountability {

        private void stubUpToTheRules() {
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
        }

        @Test
        @DisplayName("an adjustment with no reason is refused")
        void adjustmentWithoutReason() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("100.00"));
            dto.setReason("  ");
            stubUpToTheRules();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a lot-tracked item cannot be adjusted without a lot")
        void lotTrackedItemWithoutLot() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("100.00"));
            dto.setStockLotId(null);
            stubUpToTheRules();
            when(stockLotRepository.existsByInventoryItemId(inventoryItemId)).thenReturn(true);

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(inventoryItemRepository, never()).incrementOnHand(any(), any(), any());
        }

        @Test
        @DisplayName("another item's lot is refused")
        void lotOfAnotherItem() {
            InventoryItem other = InventoryItem.builder().pharmacy(pharmacy).build();
            other.setId(UUID.randomUUID());
            stockLot.setInventoryItem(other);
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("5.00"));
            stubUpToTheRules();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a movement booked in a colleague's name is refused")
        void performerMustBeTheCaller() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("5.00"));
            dto.setPerformedBy(UUID.randomUUID());
            stubUpToTheRules();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("D108: nothing is received into an expired lot")
        void receiptIntoExpiredLot() {
            stockLot.setExpiryDate(LocalDate.now().minusDays(1));
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RECEIPT, new BigDecimal("5.00"));
            stubUpToTheRules();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(stockTransactionRepository, never()).save(any());
        }
    }

    // ── recordTransaction — TRANSFER ─────────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — TRANSFER")
    class RecordTransfer {

        @Test
        @DisplayName("subtracts quantity and validates stock")
        void success() {
            BigDecimal qty = new BigDecimal("15.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.TRANSFER, qty);
            stubCommonMocks(dto, true);

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("85.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("35.00"));
            verify(stockLotRepository, never()).save(any(StockLot.class));
            verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
        }

        @Test
        @DisplayName("throws when insufficient stock for transfer")
        void insufficientStock() {
            stockLot.setRemainingQuantity(new BigDecimal("500.00")); // the item is what refuses
            BigDecimal qty = new BigDecimal("150.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.TRANSFER, qty);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.TRANSFER)
                    .quantity(qty)
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(dto, inventoryItem, stockLot, user))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Insufficient stock");
        }
    }

    // ── recordTransaction — ADJUSTMENT ───────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — ADJUSTMENT")
    class RecordAdjustment {

        @Test
        @DisplayName("adds positive adjustment to item and lot")
        void positiveAdjustment() {
            BigDecimal qty = new BigDecimal("5.00");
            stockLot.setInitialQuantity(new BigDecimal("60.00"));
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);
            stubCommonMocks(dto, true);

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("105.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("55.00"));
            verify(stockLotRepository, never()).save(any(StockLot.class));
        }

        @Test
        @DisplayName("QA D405: a positive adjustment above what the lot received is refused, nothing moves")
        void positiveAdjustmentAboveReceivedIsRefused() {
            // The QA: lot received 50 (50 left), adjusted by +9949.
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("9949"));
            stubCommonMocks(dto, true);
            // The refusal comes before the row is answered.
            org.mockito.Mockito.reset(stockTransactionMapper);
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), any(), eq(user)))
                    .thenReturn(StockTransaction.builder().inventoryItem(inventoryItem).stockLot(stockLot)
                            .transactionType(dto.getTransactionType()).quantity(dto.getQuantity())
                            .reason(dto.getReason()).performedByUser(user).build());
            org.mockito.Mockito.reset(stockTransactionRepository);
            when(stockTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("received"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("100.00"));
            verify(inventoryItemRepository, never()).incrementOnHand(any(), any(), any());
        }

        @Test
        @DisplayName("subtracts negative adjustment from item and lot")
        void negativeAdjustment() {
            BigDecimal qty = new BigDecimal("-10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);
            stubCommonMocks(dto, true);

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("40.00"));
        }
    }

    // ── QA D143 / D157: write-offs and the controlled-substance witness ─────

    @Nested
    @DisplayName("QA D143 / D157: write-offs and witnesses")
    class WriteOffAndWitness {

        private final UUID witnessId = UUID.randomUUID();

        private void makeControlled() {
            inventoryItem.getMedicationCatalogItem().setControlled(true);
        }

        private void stubUpToTheWitness() {
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        }

        @Test
        @DisplayName("D143: a write-off larger than the lot is refused and nothing moves")
        void writeOffBeyondTheLotIsRefused() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("-60.00"));
            stubUpToTheWitness();
            StockTransaction tx = StockTransaction.builder().inventoryItem(inventoryItem).stockLot(stockLot)
                    .transactionType(dto.getTransactionType()).quantity(dto.getQuantity()).performedByUser(user).build();
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), any(), eq(user))).thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("D157: a controlled-substance write-off with no witness is refused")
        void controlledAdjustmentWithoutWitnessIsRefused() {
            makeControlled();
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("-1.00"));
            stubUpToTheWitness();

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().contains("stockTransaction.witness.required") || e.getMessage().contains("witness"));
            verify(stockTransactionRepository, never()).save(any());
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("D157: the performer cannot witness their own movement")
        void selfWitnessIsRefused() {
            makeControlled();
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("-1.00"));
            dto.setWitnessedBy(userId);
            stubUpToTheWitness();

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().contains("stockTransaction.witness.notSelf") || e.getMessage().contains("someone other"));
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("D157: a witness with no stock role at this hospital is refused")
        void witnessFromNowhereIsRefused() {
            makeControlled();
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("-1.00"));
            dto.setWitnessedBy(witnessId);
            stubUpToTheWitness();
            when(assignmentRepository.existsActiveWitness(
                    witnessId, hospitalId, StockTransactionServiceImpl.WITNESS_ROLE_CODES)).thenReturn(false);

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().contains("stockTransaction.witness.invalid") || e.getMessage().contains("of this hospital"));
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("D157: a witnessed controlled write-off is recorded with its witness and takes stock off")
        void witnessedControlledWriteOffIsRecorded() {
            makeControlled();
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, new BigDecimal("-1.00"));
            dto.setWitnessedBy(witnessId);
            User witness = new User();
            witness.setId(witnessId);
            stubCommonMocks(dto, true);
            when(assignmentRepository.existsActiveWitness(
                    witnessId, hospitalId, StockTransactionServiceImpl.WITNESS_ROLE_CODES)).thenReturn(true);
            when(userRepository.findById(witnessId)).thenReturn(Optional.of(witness));
            org.mockito.ArgumentCaptor<StockTransaction> saved = org.mockito.ArgumentCaptor.forClass(StockTransaction.class);

            service.recordTransaction(dto);

            verify(stockTransactionRepository).save(saved.capture());
            assertThat(saved.getValue().getWitnessedByUser()).isSameAs(witness);
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("49.00"));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("99.00"));
        }

        @Test
        @DisplayName("D157: witness candidates are the other active stock-role holders of the hospital")
        void witnessCandidates() {
            stubHospitalScope();
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            com.example.hms.model.Role pharmacist = new com.example.hms.model.Role();
            pharmacist.setCode("ROLE_PHARMACIST");
            com.example.hms.model.Role nurse = new com.example.hms.model.Role();
            nurse.setCode("ROLE_NURSE");
            User colleague = new User();
            colleague.setId(witnessId);
            colleague.setFirstName("Awa");
            colleague.setLastName("Traore");
            User nurseUser = new User();
            nurseUser.setId(UUID.randomUUID());
            User retired = new User();
            retired.setId(UUID.randomUUID());
            User deactivated = new User();
            deactivated.setId(UUID.randomUUID());
            deactivated.setActive(false);
            when(assignmentRepository.findStaffRowsByHospitalId(hospitalId)).thenReturn(List.of(
                    assignment(user, pharmacist, true),
                    assignment(colleague, pharmacist, true),
                    assignment(nurseUser, nurse, true),
                    assignment(retired, pharmacist, false),
                    assignment(deactivated, pharmacist, true)));

            var candidates = service.listWitnessCandidates();

            assertThat(candidates).extracting(c -> c.userId()).containsExactly(witnessId);
            assertThat(candidates.get(0).displayName()).isEqualTo("Awa Traore");
        }

        private com.example.hms.model.UserRoleHospitalAssignment assignment(
                User u, com.example.hms.model.Role role, boolean active) {
            com.example.hms.model.UserRoleHospitalAssignment a = new com.example.hms.model.UserRoleHospitalAssignment();
            a.setUser(u);
            a.setRole(role);
            a.setActive(active);
            return a;
        }
    }

    // ── recordTransaction — RETURN ───────────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — RETURN")
    class RecordReturn {

        @Test
        @DisplayName("adds quantity to item and lot")
        void success() {
            BigDecimal qty = new BigDecimal("8.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RETURN, qty);
            stubCommonMocks(dto, true);
            stubLotMovements(new BigDecimal("10.00"), new BigDecimal("2.00"));

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("108.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("58.00"));
            verify(stockLotRepository, never()).save(any(StockLot.class));
            verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
        }

        private void stubLotMovements(BigDecimal issued, BigDecimal returned) {
            when(stockTransactionRepository.sumQuantityForLot(stockLotId, java.util.EnumSet.of(
                    StockTransactionType.DISPENSE, StockTransactionType.TRANSFER))).thenReturn(issued);
            when(stockTransactionRepository.sumQuantityForLot(stockLotId, java.util.EnumSet.of(
                    StockTransactionType.RETURN))).thenReturn(returned);
        }

        /**
         * QA D270: RETURN of 100 000 on lot QA-PARA-L1 (received at 200) was
         * accepted and the lot held 100 186. A return is bounded by what left
         * the lot and was not returned yet: here 10 out, 2 back, so 9 is refused.
         */
        @Test
        @DisplayName("refuses a return above what left the lot and was not returned yet")
        void refusesAReturnAboveWhatWasIssued() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RETURN, new BigDecimal("9.00"));
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            stubLotMovements(new BigDecimal("10.00"), new BigDecimal("2.00"));

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.return.exceedsIssued");

            verify(inventoryItemRepository).lockById(inventoryItemId);
            verify(stockTransactionRepository, never()).save(any(StockTransaction.class));
            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        private com.example.hms.model.pharmacy.Dispense dispenseOf(BigDecimal quantity,
                com.example.hms.enums.DispenseStatus status, StockLot lot) {
            com.example.hms.model.pharmacy.Dispense d = new com.example.hms.model.pharmacy.Dispense();
            d.setId(UUID.randomUUID());
            d.setQuantityDispensed(quantity);
            d.setStatus(status);
            d.setStockLot(lot);
            d.setPharmacy(pharmacy);
            return d;
        }

        private StockTransactionRequestDTO returnAgainst(com.example.hms.model.pharmacy.Dispense d, String qty) {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RETURN, new BigDecimal(qty));
            dto.setReferenceId(d.getId());
            return dto;
        }

        /**
         * QA D604, the exact scenario: dispense 63a1f3fb delivered 4; a
         * RETURN of 2 referencing it was taken, then a RETURN of 3. The lot
         * had issued plenty, so only the per-dispense bound can refuse it.
         */
        @Test
        @DisplayName("D604: a return cannot give back more than its dispense delivered minus earlier returns")
        void refusesAReturnAboveWhatTheDispenseDelivered() {
            var dispense = dispenseOf(new BigDecimal("4"), com.example.hms.enums.DispenseStatus.COMPLETED, stockLot);
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseRepository.findLockedById(dispense.getId())).thenReturn(Optional.of(dispense));
            when(stockTransactionRepository.sumQuantityForReference(dispense.getId(),
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(new BigDecimal("2"));

            StockTransactionRequestDTO request = returnAgainst(dispense, "3");
            assertThatThrownBy(() -> service.recordTransaction(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.return.exceedsDispensed");

            verify(stockTransactionRepository, never()).save(any(StockTransaction.class));
            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
        }

        @Test
        @DisplayName("D604: a return within what its dispense delivered still goes back on the lot")
        void acceptsAReturnWithinTheDispense() {
            var dispense = dispenseOf(new BigDecimal("4"), com.example.hms.enums.DispenseStatus.COMPLETED, stockLot);
            StockTransactionRequestDTO dto = returnAgainst(dispense, "2");
            stubCommonMocks(dto, true);
            stubLotMovements(new BigDecimal("40.00"), BigDecimal.ZERO);
            when(dispenseRepository.findLockedById(dispense.getId())).thenReturn(Optional.of(dispense));
            when(stockTransactionRepository.sumQuantityForReference(dispense.getId(),
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(BigDecimal.ZERO);

            service.recordTransaction(dto);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("52.00"));
            // The dispense row is locked before the item, the order its cancel takes.
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(dispenseRepository, inventoryItemRepository);
            order.verify(dispenseRepository).findLockedById(dispense.getId());
            order.verify(inventoryItemRepository).lockById(inventoryItemId);
        }

        @Test
        @DisplayName("D604: nothing is returned against a cancelled dispense (its cancel already put it back)")
        void refusesAReturnAgainstACancelledDispense() {
            var dispense = dispenseOf(new BigDecimal("4"), com.example.hms.enums.DispenseStatus.CANCELLED, stockLot);
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseRepository.findLockedById(dispense.getId())).thenReturn(Optional.of(dispense));

            StockTransactionRequestDTO request = returnAgainst(dispense, "1");
            assertThatThrownBy(() -> service.recordTransaction(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MessageUtil.resolve("stockTransaction.return.dispenseNotReturnable"));
            verify(stockTransactionRepository, never()).save(any(StockTransaction.class));
        }

        @Test
        @DisplayName("D604: a return naming a dispense of another lot is refused")
        void refusesAReturnOnAnotherLot() {
            StockLot other = StockLot.builder().inventoryItem(inventoryItem).lotNumber("LOT-002").build();
            other.setId(UUID.randomUUID());
            var dispense = dispenseOf(new BigDecimal("4"), com.example.hms.enums.DispenseStatus.COMPLETED, other);
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseRepository.findLockedById(dispense.getId())).thenReturn(Optional.of(dispense));

            StockTransactionRequestDTO request = returnAgainst(dispense, "1");
            assertThatThrownBy(() -> service.recordTransaction(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MessageUtil.resolve("stockTransaction.return.wrongDispense"));
        }

        @Test
        @DisplayName("D604: an item kept without lots is bounded by its partial dispense from the same pharmacy")
        void acceptsNothingAbovePartialItemDispense() {
            var dispense = dispenseOf(new BigDecimal("3"), com.example.hms.enums.DispenseStatus.PARTIAL, null);
            StockTransactionRequestDTO request = returnAgainst(dispense, "3");
            request.setStockLotId(null);
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(dispenseRepository.findLockedById(dispense.getId())).thenReturn(Optional.of(dispense));
            when(stockTransactionRepository.sumQuantityForReference(dispense.getId(),
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(new BigDecimal("1"));

            assertThatThrownBy(() -> service.recordTransaction(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.return.exceedsDispensed");
            verify(stockTransactionRepository, never()).save(any(StockTransaction.class));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("D604: a lot-less return naming a dispense of another pharmacy is refused")
        void refusesAnItemReturnAgainstAnotherPharmacysDispense() {
            Pharmacy elsewhere = Pharmacy.builder().hospital(hospital).name("Annex").build();
            elsewhere.setId(UUID.randomUUID());
            var dispense = dispenseOf(new BigDecimal("4"), com.example.hms.enums.DispenseStatus.COMPLETED, null);
            dispense.setPharmacy(elsewhere);
            StockTransactionRequestDTO request = returnAgainst(dispense, "1");
            request.setStockLotId(null);
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(dispenseRepository.findLockedById(dispense.getId())).thenReturn(Optional.of(dispense));

            assertThatThrownBy(() -> service.recordTransaction(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MessageUtil.resolve("stockTransaction.return.wrongDispense"));
            verify(stockTransactionRepository, never()).save(any(StockTransaction.class));
        }

        @Test
        @DisplayName("D604: a reference that is no dispense (a transfer) keeps only the lot bound")
        void aNonDispenseReferenceFallsBackToTheLotBound() {
            StockTransactionRequestDTO request = buildRequest(StockTransactionType.RETURN, new BigDecimal("9.00"));
            UUID transferId = UUID.randomUUID();
            request.setReferenceId(transferId);
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseRepository.findLockedById(transferId)).thenReturn(Optional.empty());
            stubLotMovements(new BigDecimal("10.00"), new BigDecimal("2.00"));

            assertThatThrownBy(() -> service.recordTransaction(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.return.exceedsIssued");
            verify(stockTransactionRepository, never()).sumQuantityForReference(any(), any());
        }

        @Test
        @DisplayName("refuses any return on a lot nothing left")
        void refusesAReturnOnALotNothingLeft() {
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RETURN, new BigDecimal("100000"));
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            stubLotMovements(BigDecimal.ZERO, BigDecimal.ZERO);

            assertThatThrownBy(() -> service.recordTransaction(dto)).isInstanceOf(BusinessException.class);
            verify(stockTransactionRepository, never()).save(any(StockTransaction.class));
        }
    }

    // ── recordTransaction — without lot ──────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — without lot")
    class RecordWithoutLot {

        @Test
        @DisplayName("updates only item quantity, not lot")
        void noLot() {
            BigDecimal qty = new BigDecimal("25.00");
            // A positive adjustment of an item kept without lots (a receipt
            // is refused here since QA D615).
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);
            dto.setStockLotId(null);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(null)
                    .transactionType(StockTransactionType.ADJUSTMENT)
                    .quantity(qty)
                    .reason("Test reason")
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), isNull(), eq(user)))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(buildResponse());

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("125.00"));
            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
        }
    }

    // ── getTransaction ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getTransaction")
    class GetTransaction {

        @Test
        @DisplayName("returns transaction when found")
        void success() {
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(new BigDecimal("20.00"))
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            StockTransactionResponseDTO responseDTO = buildResponse();

            when(stockTransactionRepository.findById(transactionId)).thenReturn(Optional.of(tx));
            stubHospitalScope();
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(responseDTO);

            StockTransactionResponseDTO result = service.getTransaction(transactionId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(transactionId);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when not found")
        void notFound() {
            when(stockTransactionRepository.findById(transactionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTransaction(transactionId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.notFound");
        }
    }

    // ── listByInventoryItem ──────────────────────────────────────────────

    @Nested
    @DisplayName("listByInventoryItem")
    class ListByInventoryItem {

        @Test
        @DisplayName("returns page of transactions")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(new BigDecimal("20.00"))
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            Page<StockTransaction> entityPage = new PageImpl<>(List.of(tx));
            StockTransactionResponseDTO responseDTO = buildResponse();

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockTransactionRepository.findByInventoryItemId(inventoryItemId, pageable))
                    .thenReturn(entityPage);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(responseDTO);

            Page<StockTransactionResponseDTO> result = service.listByInventoryItem(inventoryItemId, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(transactionId);
        }
    }

    // ── listByPharmacy ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listByPharmacy")
    class ListByPharmacy {

        @Test
        @DisplayName("returns page of transactions for pharmacy")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .transactionType(StockTransactionType.DISPENSE)
                    .quantity(new BigDecimal("5.00"))
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            Page<StockTransaction> entityPage = new PageImpl<>(List.of(tx));
            StockTransactionResponseDTO responseDTO = buildResponse();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(stockTransactionRepository.findByInventoryItemPharmacyId(pharmacyId, pageable))
                    .thenReturn(entityPage);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(responseDTO);

            Page<StockTransactionResponseDTO> result = service.listByPharmacy(pharmacyId, pageable);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when pharmacy not found")
        void pharmacyNotFound() {
            Pageable pageable = PageRequest.of(0, 20);
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listByPharmacy(pharmacyId, pageable))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "pharmacy.notFound");
        }
    }

    // ── listByPharmacyAndDateRange ───────────────────────────────────────

    @Nested
    @DisplayName("listByPharmacyAndDateRange")
    class ListByPharmacyAndDateRange {

        @Test
        @DisplayName("returns page filtered by date range")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            LocalDateTime from = LocalDateTime.now().minusDays(7);
            LocalDateTime to = LocalDateTime.now();
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(new BigDecimal("30.00"))
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            Page<StockTransaction> entityPage = new PageImpl<>(List.of(tx));
            StockTransactionResponseDTO responseDTO = buildResponse();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(stockTransactionRepository.findByPharmacyAndDateRange(pharmacyId, from, to, pageable))
                    .thenReturn(entityPage);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(responseDTO);

            Page<StockTransactionResponseDTO> result =
                    service.listByPharmacyAndDateRange(pharmacyId, from, to, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(stockTransactionRepository).findByPharmacyAndDateRange(pharmacyId, from, to, pageable);
        }
    }

    // ── listByPharmacyAndType ────────────────────────────────────────────

    @Nested
    @DisplayName("listByPharmacyAndType")
    class ListByPharmacyAndType {

        @Test
        @DisplayName("returns page filtered by transaction type")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            StockTransactionType type = StockTransactionType.DISPENSE;
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .transactionType(type)
                    .quantity(new BigDecimal("10.00"))
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            Page<StockTransaction> entityPage = new PageImpl<>(List.of(tx));
            StockTransactionResponseDTO responseDTO = buildResponse();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(stockTransactionRepository.findByInventoryItemPharmacyIdAndTransactionType(
                    pharmacyId, type, pageable)).thenReturn(entityPage);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(responseDTO);

            Page<StockTransactionResponseDTO> result =
                    service.listByPharmacyAndType(pharmacyId, type, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(stockTransactionRepository)
                    .findByInventoryItemPharmacyIdAndTransactionType(pharmacyId, type, pageable);
        }
    }

    // ── enforceHospitalScope ─────────────────────────────────────────────

    @Nested
    @DisplayName("enforceHospitalScope")
    class EnforceHospitalScope {

        @Test
        @DisplayName("throws when pharmacy belongs to different hospital")
        void differentHospital() {
            UUID differentHospitalId = UUID.randomUUID();
            when(roleValidator.requireActiveHospitalId()).thenReturn(differentHospitalId);

            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(new BigDecimal("10.00"))
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);

            when(stockTransactionRepository.findById(transactionId)).thenReturn(Optional.of(tx));

            assertThatThrownBy(() -> service.getTransaction(transactionId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "pharmacy.notFound");
        }
    }

    // ── resolvePerformer ─────────────────────────────────────────────────

    @Nested
    @DisplayName("resolvePerformer")
    class ResolvePerformer {

        @Test
        @DisplayName("uses current user when performedBy is null")
        void resolvesCurrentUser() {
            BigDecimal qty = new BigDecimal("-10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);
            dto.setPerformedBy(null);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.ADJUSTMENT)
                    .quantity(qty)
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(dto, inventoryItem, stockLot, user))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(buildResponse());

            StockTransactionResponseDTO result = service.recordTransaction(dto);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws BusinessException when current user cannot be determined")
        void cannotDetermineCurrentUser() {
            BigDecimal qty = new BigDecimal("-10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);
            dto.setPerformedBy(null);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(null);

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unable to determine performing user");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when the signed-in user is not found")
        void performerNotFound() {
            BigDecimal qty = new BigDecimal("-10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "user.current.notfound");
        }
    }
}
