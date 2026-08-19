package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.InventoryItemMapper;
import com.example.hms.mapper.pharmacy.StockLotMapper;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.NotificationService;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private MedicationCatalogItemRepository medicationCatalogItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private InventoryItemMapper inventoryItemMapper;
    @Mock private StockLotMapper stockLotMapper;
    @Mock private StockTransactionMapper stockTransactionMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private InventoryServiceImpl service;

    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID medicationId = UUID.randomUUID();
    private final UUID inventoryItemId = UUID.randomUUID();
    private final UUID stockLotId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Hospital hospital;
    private Pharmacy pharmacy;
    private MedicationCatalogItem medication;
    private InventoryItem inventoryItem;
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

        medication = new MedicationCatalogItem();
        medication.setId(medicationId);
        medication.setNameFr("Paracétamol");

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

        user = new User();
        user.setId(userId);
        user.setUsername("pharmacist1");
    }

    private InventoryItemRequestDTO buildItemRequest() {
        return InventoryItemRequestDTO.builder()
                .pharmacyId(pharmacyId)
                .medicationCatalogItemId(medicationId)
                .quantityOnHand(new BigDecimal("100.00"))
                .reorderThreshold(new BigDecimal("10.00"))
                .reorderQuantity(new BigDecimal("50.00"))
                .unit("tablets")
                .build();
    }

    private InventoryItemResponseDTO buildItemResponse() {
        return InventoryItemResponseDTO.builder()
                .id(inventoryItemId)
                .pharmacyId(pharmacyId)
                .medicationCatalogItemId(medicationId)
                .medicationName("Paracétamol")
                .quantityOnHand(new BigDecimal("100.00"))
                .reorderThreshold(new BigDecimal("10.00"))
                .reorderQuantity(new BigDecimal("50.00"))
                .unit("tablets")
                .active(true)
                .build();
    }

    private void stubHospitalScope() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private void stubHospitalScopeNull() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
    }

    // ── createInventoryItem ──────────────────────────────────────────────

    @Nested
    @DisplayName("createInventoryItem")
    class CreateInventoryItem {

        @Test
        @DisplayName("creates item successfully")
        void success() {
            InventoryItemRequestDTO dto = buildItemRequest();
            InventoryItemResponseDTO responseDTO = buildItemResponse();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(medicationCatalogItemRepository.findById(medicationId)).thenReturn(Optional.of(medication));
            when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, medicationId))
                    .thenReturn(Optional.empty());
            when(inventoryItemMapper.toEntity(dto, pharmacy, medication)).thenReturn(inventoryItem);
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            InventoryItemResponseDTO result = service.createInventoryItem(dto);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(inventoryItemId);
            assertThat(result.getPharmacyId()).isEqualTo(pharmacyId);
            verify(inventoryItemRepository).save(inventoryItem);
        }

        @Test
        @DisplayName("throws BusinessException when duplicate exists")
        void duplicateThrows() {
            InventoryItemRequestDTO dto = buildItemRequest();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(medicationCatalogItemRepository.findById(medicationId)).thenReturn(Optional.of(medication));
            when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, medicationId))
                    .thenReturn(Optional.of(inventoryItem));

            assertThatThrownBy(() -> service.createInventoryItem(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when pharmacy not found")
        void pharmacyNotFound() {
            InventoryItemRequestDTO dto = buildItemRequest();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createInventoryItem(dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Pharmacy not found");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when medication not found")
        void medicationNotFound() {
            InventoryItemRequestDTO dto = buildItemRequest();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(medicationCatalogItemRepository.findById(medicationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createInventoryItem(dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Medication catalog item not found");
        }
    }

    // ── getInventoryItem ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getInventoryItem")
    class GetInventoryItem {

        @Test
        @DisplayName("returns item when found")
        void success() {
            InventoryItemResponseDTO responseDTO = buildItemResponse();

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            InventoryItemResponseDTO result = service.getInventoryItem(inventoryItemId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(inventoryItemId);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when not found")
        void notFound() {
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getInventoryItem(inventoryItemId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Inventory item not found");
        }
    }

    // ── listByPharmacy ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listByPharmacy")
    class ListByPharmacy {

        @Test
        @DisplayName("returns page of items")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            InventoryItemResponseDTO responseDTO = buildItemResponse();
            Page<InventoryItem> entityPage = new PageImpl<>(List.of(inventoryItem));

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(inventoryItemRepository.findByPharmacyIdAndActiveTrue(pharmacyId, pageable))
                    .thenReturn(entityPage);
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            Page<InventoryItemResponseDTO> result = service.listByPharmacy(pharmacyId, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(inventoryItemId);
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

    // ── listByHospital ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listByHospital")
    class ListByHospital {

        @Test
        @DisplayName("returns items filtered by hospital when hospitalId present")
        void withHospitalId() {
            Pageable pageable = PageRequest.of(0, 20);
            InventoryItemResponseDTO responseDTO = buildItemResponse();
            Page<InventoryItem> entityPage = new PageImpl<>(List.of(inventoryItem));

            stubHospitalScope();
            when(inventoryItemRepository.findByPharmacyHospitalIdAndActiveTrue(hospitalId, pageable))
                    .thenReturn(entityPage);
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            Page<InventoryItemResponseDTO> result = service.listByHospital(pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(inventoryItemRepository).findByPharmacyHospitalIdAndActiveTrue(hospitalId, pageable);
            verify(inventoryItemRepository, never()).findAll(pageable);
        }

        @Test
        @DisplayName("returns all items when hospitalId is null")
        void withoutHospitalId() {
            Pageable pageable = PageRequest.of(0, 20);
            InventoryItemResponseDTO responseDTO = buildItemResponse();
            Page<InventoryItem> entityPage = new PageImpl<>(List.of(inventoryItem));

            stubHospitalScopeNull();
            when(inventoryItemRepository.findAll(pageable)).thenReturn(entityPage);
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            Page<InventoryItemResponseDTO> result = service.listByHospital(pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(inventoryItemRepository).findAll(pageable);
            verify(inventoryItemRepository, never()).findByPharmacyHospitalIdAndActiveTrue(any(), any());
        }
    }

    // ── updateInventoryItem ──────────────────────────────────────────────

    @Nested
    @DisplayName("updateInventoryItem")
    class UpdateInventoryItem {

        @Test
        @DisplayName("updates item successfully")
        void success() {
            InventoryItemRequestDTO dto = buildItemRequest();
            InventoryItemResponseDTO responseDTO = buildItemResponse();

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            InventoryItemResponseDTO result = service.updateInventoryItem(inventoryItemId, dto);

            assertThat(result).isNotNull();
            verify(inventoryItemMapper).updateEntity(inventoryItem, dto);
            verify(inventoryItemRepository).save(inventoryItem);
        }
    }

    // ── deactivateInventoryItem ──────────────────────────────────────────

    @Nested
    @DisplayName("deactivateInventoryItem")
    class DeactivateInventoryItem {

        @Test
        @DisplayName("sets active=false and saves")
        void success() {
            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);

            service.deactivateInventoryItem(inventoryItemId);

            assertThat(inventoryItem.isActive()).isFalse();
            verify(inventoryItemRepository).save(inventoryItem);
        }
    }

    // ── receiveStock ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("receiveStock")
    class ReceiveStock {

        private StockLotRequestDTO buildLotRequest() {
            return StockLotRequestDTO.builder()
                    .inventoryItemId(inventoryItemId)
                    .lotNumber("LOT-001")
                    .expiryDate(LocalDate.now().plusYears(1))
                    .initialQuantity(new BigDecimal("50.00"))
                    .remainingQuantity(new BigDecimal("50.00"))
                    .supplier("PharmaCo")
                    .unitCost(new BigDecimal("2.50"))
                    .receivedDate(LocalDate.now())
                    .receivedBy(userId)
                    .notes("First lot")
                    .build();
        }

        private StockLot buildStockLot() {
            StockLot lot = StockLot.builder()
                    .inventoryItem(inventoryItem)
                    .lotNumber("LOT-001")
                    .expiryDate(LocalDate.now().plusYears(1))
                    .initialQuantity(new BigDecimal("50.00"))
                    .remainingQuantity(new BigDecimal("50.00"))
                    .supplier("PharmaCo")
                    .unitCost(new BigDecimal("2.50"))
                    .receivedDate(LocalDate.now())
                    .receivedByUser(user)
                    .notes("First lot")
                    .build();
            lot.setId(stockLotId);
            return lot;
        }

        @Test
        @DisplayName("saves lot, updates quantity on hand, records transaction, logs audit")
        void success() {
            StockLotRequestDTO dto = buildLotRequest();
            StockLot lot = buildStockLot();
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder()
                    .id(stockLotId)
                    .inventoryItemId(inventoryItemId)
                    .lotNumber("LOT-001")
                    .remainingQuantity(new BigDecimal("50.00"))
                    .build();

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotMapper.toEntity(dto, inventoryItem, user)).thenReturn(lot);
            when(stockLotRepository.save(lot)).thenReturn(lot);
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockTransactionRepository.save(any(StockTransaction.class)))
                    .thenAnswer(inv -> {
                        StockTransaction tx = inv.getArgument(0);
                        tx.setId(UUID.randomUUID());
                        return tx;
                    });
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            StockLotResponseDTO result = service.receiveStock(dto);

            // 1. Lot is saved
            verify(stockLotRepository).save(lot);

            // 2. Item quantity on hand is increased by lot's remaining quantity
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(new BigDecimal("150.00"));

            // 3. Inventory item is saved with updated quantity
            verify(inventoryItemRepository).save(inventoryItem);

            // 4. RECEIPT stock transaction is recorded
            ArgumentCaptor<StockTransaction> txCaptor = ArgumentCaptor.forClass(StockTransaction.class);
            verify(stockTransactionRepository).save(txCaptor.capture());
            StockTransaction savedTx = txCaptor.getValue();
            assertThat(savedTx.getTransactionType()).isEqualTo(StockTransactionType.RECEIPT);
            assertThat(savedTx.getQuantity()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(savedTx.getInventoryItem()).isEqualTo(inventoryItem);
            assertThat(savedTx.getStockLot()).isEqualTo(lot);

            // 5. Audit event is logged
            verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));

            // Response is correct
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(stockLotId);
        }

        @Test
        @DisplayName("resolves current user when receivedBy is null")
        void resolvesCurrentUser() {
            StockLotRequestDTO dto = buildLotRequest();
            dto.setReceivedBy(null);
            StockLot lot = buildStockLot();
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder()
                    .id(stockLotId)
                    .build();

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotMapper.toEntity(dto, inventoryItem, user)).thenReturn(lot);
            when(stockLotRepository.save(lot)).thenReturn(lot);
            when(inventoryItemRepository.save(inventoryItem)).thenReturn(inventoryItem);
            when(stockTransactionRepository.save(any(StockTransaction.class)))
                    .thenAnswer(inv -> {
                        StockTransaction tx = inv.getArgument(0);
                        tx.setId(UUID.randomUUID());
                        return tx;
                    });
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            StockLotResponseDTO result = service.receiveStock(dto);

            assertThat(result).isNotNull();
        }
    }

    // ── getStockLot ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStockLot")
    class GetStockLot {

        @Test
        @DisplayName("returns lot when found")
        void success() {
            StockLot lot = StockLot.builder()
                    .inventoryItem(inventoryItem)
                    .lotNumber("LOT-001")
                    .build();
            lot.setId(stockLotId);
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder()
                    .id(stockLotId)
                    .lotNumber("LOT-001")
                    .build();

            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(lot));
            stubHospitalScope();
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            StockLotResponseDTO result = service.getStockLot(stockLotId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(stockLotId);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when not found")
        void notFound() {
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getStockLot(stockLotId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Stock lot not found");
        }
    }

    // ── listLotsByInventoryItem ──────────────────────────────────────────

    @Nested
    @DisplayName("listLotsByInventoryItem")
    class ListLotsByInventoryItem {

        @Test
        @DisplayName("returns page of lots")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            StockLot lot = StockLot.builder().inventoryItem(inventoryItem).lotNumber("LOT-001").build();
            lot.setId(stockLotId);
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder().id(stockLotId).build();
            Page<StockLot> entityPage = new PageImpl<>(List.of(lot));

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScope();
            when(stockLotRepository.findByInventoryItemId(inventoryItemId, pageable)).thenReturn(entityPage);
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            Page<StockLotResponseDTO> result = service.listLotsByInventoryItem(inventoryItemId, pageable);

            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ── listLotsByPharmacy ───────────────────────────────────────────────

    @Nested
    @DisplayName("listLotsByPharmacy")
    class ListLotsByPharmacy {

        @Test
        @DisplayName("returns page of lots for pharmacy")
        void returnsPage() {
            Pageable pageable = PageRequest.of(0, 20);
            StockLot lot = StockLot.builder().inventoryItem(inventoryItem).lotNumber("LOT-001").build();
            lot.setId(stockLotId);
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder().id(stockLotId).build();
            Page<StockLot> entityPage = new PageImpl<>(List.of(lot));

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(stockLotRepository.findByInventoryItemPharmacyId(pharmacyId, pageable)).thenReturn(entityPage);
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            Page<StockLotResponseDTO> result = service.listLotsByPharmacy(pharmacyId, pageable);

            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ── getExpiringSoon ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getExpiringSoon")
    class GetExpiringSoon {

        @Test
        @DisplayName("returns lots expiring within specified days")
        void returnsList() {
            StockLot lot = StockLot.builder()
                    .inventoryItem(inventoryItem)
                    .lotNumber("LOT-EXPIRE")
                    .expiryDate(LocalDate.now().plusDays(15))
                    .build();
            lot.setId(stockLotId);
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder()
                    .id(stockLotId)
                    .lotNumber("LOT-EXPIRE")
                    .build();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(stockLotRepository.findExpiringSoon(eq(pharmacyId), any(LocalDate.class)))
                    .thenReturn(List.of(lot));
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            List<StockLotResponseDTO> result = service.getExpiringSoon(pharmacyId, 30);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLotNumber()).isEqualTo("LOT-EXPIRE");
        }
    }

    // ── updateStockLot ───────────────────────────────────────────────────

    @Nested
    @DisplayName("updateStockLot")
    class UpdateStockLot {

        @Test
        @DisplayName("updates lot successfully")
        void success() {
            StockLot lot = StockLot.builder()
                    .inventoryItem(inventoryItem)
                    .lotNumber("LOT-001")
                    .build();
            lot.setId(stockLotId);
            StockLotRequestDTO dto = StockLotRequestDTO.builder()
                    .inventoryItemId(inventoryItemId)
                    .lotNumber("LOT-001-UPDATED")
                    .expiryDate(LocalDate.now().plusYears(2))
                    .initialQuantity(new BigDecimal("100.00"))
                    .receivedDate(LocalDate.now())
                    .build();
            StockLotResponseDTO responseDTO = StockLotResponseDTO.builder()
                    .id(stockLotId)
                    .lotNumber("LOT-001-UPDATED")
                    .build();

            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(lot));
            stubHospitalScope();
            when(stockLotRepository.save(lot)).thenReturn(lot);
            when(stockLotMapper.toResponseDTO(lot)).thenReturn(responseDTO);

            StockLotResponseDTO result = service.updateStockLot(stockLotId, dto);

            assertThat(result).isNotNull();
            assertThat(result.getLotNumber()).isEqualTo("LOT-001-UPDATED");
            verify(stockLotMapper).updateEntity(lot, dto);
            verify(stockLotRepository).save(lot);
        }
    }

    // ── getItemsBelowReorderThreshold ────────────────────────────────────

    @Nested
    @DisplayName("getItemsBelowReorderThreshold")
    class GetItemsBelowReorderThreshold {

        @Test
        @DisplayName("returns low-stock items for pharmacy")
        void returnsList() {
            InventoryItemResponseDTO responseDTO = buildItemResponse();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            stubHospitalScope();
            when(inventoryItemRepository.findBelowReorderThreshold(pharmacyId))
                    .thenReturn(List.of(inventoryItem));
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            List<InventoryItemResponseDTO> result = service.getItemsBelowReorderThreshold(pharmacyId);

            assertThat(result).hasSize(1);
        }
    }

    // ── getItemsBelowReorderThresholdByHospital ──────────────────────────

    @Nested
    @DisplayName("getItemsBelowReorderThresholdByHospital")
    class GetItemsBelowReorderThresholdByHospital {

        @Test
        @DisplayName("returns low-stock items when hospitalId present")
        void withHospitalId() {
            InventoryItemResponseDTO responseDTO = buildItemResponse();

            stubHospitalScope();
            when(inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId))
                    .thenReturn(List.of(inventoryItem));
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            List<InventoryItemResponseDTO> result = service.getItemsBelowReorderThresholdByHospital();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("returns empty list when hospitalId is null")
        void withoutHospitalId() {
            stubHospitalScopeNull();

            List<InventoryItemResponseDTO> result = service.getItemsBelowReorderThresholdByHospital();

            assertThat(result).isEmpty();
            verify(inventoryItemRepository, never()).findBelowReorderThresholdByHospital(any());
        }
    }

    // ── triggerReorderAlerts ─────────────────────────────────────────────

    @Nested
    @DisplayName("triggerReorderAlerts")
    class TriggerReorderAlerts {

        @Test
        @DisplayName("sends notifications for low-stock items")
        void sendsNotifications() {
            stubHospitalScope();
            when(inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId))
                    .thenReturn(List.of(inventoryItem));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            service.triggerReorderAlerts();

            verify(notificationService).createNotification(
                    any(String.class), eq("pharmacist1"), eq("REORDER_ALERT"));
            verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
        }

        @Test
        @DisplayName("does nothing when hospitalId is null")
        void noHospitalId() {
            stubHospitalScopeNull();

            service.triggerReorderAlerts();

            verify(inventoryItemRepository, never()).findBelowReorderThresholdByHospital(any());
            verify(notificationService, never()).createNotification(any(), any(), any());
        }

        @Test
        @DisplayName("continues when notification sending fails for an item")
        void handlesNotificationFailure() {
            InventoryItem secondItem = InventoryItem.builder()
                    .pharmacy(pharmacy)
                    .medicationCatalogItem(medication)
                    .quantityOnHand(new BigDecimal("3.00"))
                    .reorderThreshold(new BigDecimal("10.00"))
                    .active(true)
                    .build();
            secondItem.setId(UUID.randomUUID());

            stubHospitalScope();
            when(inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId))
                    .thenReturn(List.of(inventoryItem, secondItem));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId))
                    .thenReturn(Optional.of(user))
                    .thenReturn(Optional.of(user));
            when(notificationService.createNotification(any(), eq("pharmacist1"), eq("REORDER_ALERT")))
                    .thenThrow(new RuntimeException("Notification service down"))
                    .thenReturn(null);

            service.triggerReorderAlerts();

            // Audit is still logged for both items despite notification failure
            verify(auditEventLogService, times(2)).logEvent(any(AuditEventRequestDTO.class));
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

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));

            assertThatThrownBy(() -> service.getInventoryItem(inventoryItemId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Pharmacy not found");
        }

        @Test
        @DisplayName("allows access when hospitalId is null (superadmin)")
        void superadminAccess() {
            InventoryItemResponseDTO responseDTO = buildItemResponse();

            when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(inventoryItem));
            stubHospitalScopeNull();
            when(inventoryItemMapper.toResponseDTO(inventoryItem)).thenReturn(responseDTO);

            InventoryItemResponseDTO result = service.getInventoryItem(inventoryItemId);

            assertThat(result).isNotNull();
        }
    }
}
