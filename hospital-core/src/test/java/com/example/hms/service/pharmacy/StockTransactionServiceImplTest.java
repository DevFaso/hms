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
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(buildResponse());
    }

    // ── recordTransaction — RECEIPT ──────────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — RECEIPT")
    class RecordReceipt {

        @Test
        @DisplayName("adds quantity to item and lot")
        void success() {
            BigDecimal qty = new BigDecimal("20.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RECEIPT, qty);
            stubCommonMocks(dto, true);

            service.recordTransaction(dto);

            // Item quantity increased
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("120.00"));
            // Lot remaining increased
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("70.00"));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
            verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
        }
    }

    // ── recordTransaction — DISPENSE ─────────────────────────────────────

    @Nested
    @DisplayName("recordTransaction — DISPENSE")
    class RecordDispense {

        @Test
        @DisplayName("subtracts quantity from item and lot")
        void success() {
            BigDecimal qty = new BigDecimal("10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.DISPENSE, qty);
            stubCommonMocks(dto, true);

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("40.00"));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
        }

        @Test
        @DisplayName("throws BusinessException when insufficient item stock")
        void insufficientItemStock() {
            BigDecimal qty = new BigDecimal("200.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.DISPENSE, qty);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.DISPENSE)
                    .quantity(qty)
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), eq(stockLot), eq(user)))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Insufficient stock");
        }

        @Test
        @DisplayName("throws BusinessException when insufficient lot stock")
        void insufficientLotStock() {
            // Item has enough stock, but lot doesn't
            inventoryItem.setQuantityOnHand(new BigDecimal("500.00"));
            BigDecimal qty = new BigDecimal("60.00"); // lot only has 50
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.DISPENSE, qty);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.DISPENSE)
                    .quantity(qty)
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), eq(stockLot), eq(user)))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Insufficient lot stock");
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
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
        }

        @Test
        @DisplayName("throws when insufficient stock for transfer")
        void insufficientStock() {
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
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), eq(stockLot), eq(user)))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);

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
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.ADJUSTMENT, qty);
            stubCommonMocks(dto, true);

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("105.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("55.00"));
            verify(stockLotRepository).save(stockLot);
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

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("108.00"));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("58.00"));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
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
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RECEIPT, qty);
            dto.setStockLotId(null);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(null)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(qty)
                    .reason("Test reason")
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), eq(null), eq(user)))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(buildResponse());

            service.recordTransaction(dto);

            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("125.00"));
            verify(inventoryItemRepository).save(inventoryItem);
            verify(stockLotRepository, never()).save(any(StockLot.class));
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
                    .hasMessageContaining("Stock transaction not found");
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
                    .hasMessageContaining("Pharmacy not found");
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
                    .hasMessageContaining("Pharmacy not found");
        }
    }

    // ── resolvePerformer ─────────────────────────────────────────────────

    @Nested
    @DisplayName("resolvePerformer")
    class ResolvePerformer {

        @Test
        @DisplayName("uses current user when performedBy is null")
        void resolvesCurrentUser() {
            BigDecimal qty = new BigDecimal("10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RECEIPT, qty);
            dto.setPerformedBy(null);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.RECEIPT)
                    .quantity(qty)
                    .performedByUser(user)
                    .build();
            tx.setId(transactionId);
            when(stockTransactionMapper.toEntity(eq(dto), eq(inventoryItem), eq(stockLot), eq(user)))
                    .thenReturn(tx);
            when(stockTransactionRepository.save(tx)).thenReturn(tx);
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
            when(stockTransactionMapper.toResponseDTO(tx)).thenReturn(buildResponse());

            StockTransactionResponseDTO result = service.recordTransaction(dto);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws BusinessException when current user cannot be determined")
        void cannotDetermineCurrentUser() {
            BigDecimal qty = new BigDecimal("10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RECEIPT, qty);
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
        @DisplayName("throws ResourceNotFoundException when performer user not found")
        void performerNotFound() {
            BigDecimal qty = new BigDecimal("10.00");
            StockTransactionRequestDTO dto = buildRequest(StockTransactionType.RECEIPT, qty);

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.recordTransaction(dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Performer user not found");
        }
    }
}
