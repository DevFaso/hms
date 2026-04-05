package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabInventoryItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabInventoryItem;
import com.example.hms.payload.dto.LabInventoryItemRequestDTO;
import com.example.hms.payload.dto.LabInventoryItemResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabInventoryItemRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabInventoryServiceImplTest {

    @Mock private LabInventoryItemRepository inventoryRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private LabInventoryItemMapper mapper;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private LabInventoryServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final Locale LOCALE = Locale.ENGLISH;

    private Hospital hospital;
    private LabInventoryItem item;
    private LabInventoryItemRequestDTO requestDto;
    private LabInventoryItemResponseDTO responseDto;

    @BeforeEach
    void setUp() {
        HospitalContext context = HospitalContext.builder()
            .permittedHospitalIds(Set.of(HOSPITAL_ID))
            .activeHospitalId(HOSPITAL_ID)
            .superAdmin(false)
            .build();
        HospitalContextHolder.setContext(context);

        hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);

        item = new LabInventoryItem();
        item.setId(ITEM_ID);
        item.setName("Reagent A");
        item.setItemCode("REA-001");
        item.setHospital(hospital);
        item.setQuantity(100);
        item.setReorderThreshold(10);
        item.setActive(true);

        requestDto = new LabInventoryItemRequestDTO();
        requestDto.setName("Reagent A");
        requestDto.setItemCode("REA-001");
        requestDto.setQuantity(100);
        requestDto.setReorderThreshold(10);

        responseDto = new LabInventoryItemResponseDTO();
        responseDto.setId(ITEM_ID.toString());
        responseDto.setName("Reagent A");

        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    // ── getByHospital ────────────────────────────────────────────

    @Test
    @DisplayName("getByHospital returns paginated items")
    void getByHospitalReturnsPaginatedResults() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<LabInventoryItem> page = new PageImpl<>(List.of(item));
        when(inventoryRepository.findByHospitalIdAndActiveTrue(HOSPITAL_ID, pageable)).thenReturn(page);
        when(mapper.toDto(item)).thenReturn(responseDto);

        Page<LabInventoryItemResponseDTO> result = service.getByHospital(HOSPITAL_ID, pageable, LOCALE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Reagent A");
    }

    @Test
    @DisplayName("getByHospital throws AccessDeniedException for wrong scope")
    void getByHospitalDeniedForWrongScope() {
        UUID otherHospital = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getByHospital(otherHospital, pageable, LOCALE))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── getById ──────────────────────────────────────────────────

    @Test
    @DisplayName("getById returns item when found")
    void getByIdReturnsItem() {
        when(inventoryRepository.findByIdAndActiveTrue(ITEM_ID)).thenReturn(Optional.of(item));
        when(mapper.toDto(item)).thenReturn(responseDto);

        LabInventoryItemResponseDTO result = service.getById(ITEM_ID, LOCALE);

        assertThat(result.getId()).isEqualTo(ITEM_ID.toString());
    }

    @Test
    @DisplayName("getById throws ResourceNotFoundException when not found")
    void getByIdThrowsWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(inventoryRepository.findByIdAndActiveTrue(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(unknownId, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── create ───────────────────────────────────────────────────

    @Test
    @DisplayName("create saves and returns new item")
    void createSavesItem() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(inventoryRepository.existsByHospitalIdAndItemCode(HOSPITAL_ID, "REA-001")).thenReturn(false);
        when(mapper.toEntity(requestDto, hospital)).thenReturn(item);
        when(inventoryRepository.save(item)).thenReturn(item);
        when(mapper.toDto(item)).thenReturn(responseDto);

        LabInventoryItemResponseDTO result = service.create(HOSPITAL_ID, requestDto, LOCALE);

        assertThat(result.getName()).isEqualTo("Reagent A");
        verify(inventoryRepository).save(item);
    }

    @Test
    @DisplayName("create throws when item code is duplicated")
    void createThrowsOnDuplicateCode() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(inventoryRepository.existsByHospitalIdAndItemCode(HOSPITAL_ID, "REA-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(HOSPITAL_ID, requestDto, LOCALE))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("create throws when hospital not found")
    void createThrowsWhenHospitalNotFound() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(HOSPITAL_ID, requestDto, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create throws when quantity is negative")
    void createThrowsOnNegativeQuantity() {
        requestDto.setQuantity(-5);
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(inventoryRepository.existsByHospitalIdAndItemCode(HOSPITAL_ID, "REA-001")).thenReturn(false);

        assertThatThrownBy(() -> service.create(HOSPITAL_ID, requestDto, LOCALE))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── update ───────────────────────────────────────────────────

    @Test
    @DisplayName("update modifies and returns item")
    void updateModifiesItem() {
        when(inventoryRepository.findByIdAndActiveTrue(ITEM_ID)).thenReturn(Optional.of(item));
        when(inventoryRepository.save(item)).thenReturn(item);
        when(mapper.toDto(item)).thenReturn(responseDto);

        LabInventoryItemResponseDTO result = service.update(ITEM_ID, requestDto, LOCALE);

        assertThat(result).isNotNull();
        verify(mapper).updateEntity(item, requestDto);
    }

    @Test
    @DisplayName("update throws when item code changes to existing value")
    void updateThrowsOnDuplicateCode() {
        LabInventoryItemRequestDTO updateDto = new LabInventoryItemRequestDTO();
        updateDto.setItemCode("REA-NEW");
        updateDto.setQuantity(50);
        updateDto.setReorderThreshold(5);

        when(inventoryRepository.findByIdAndActiveTrue(ITEM_ID)).thenReturn(Optional.of(item));
        when(inventoryRepository.existsByHospitalIdAndItemCode(HOSPITAL_ID, "REA-NEW")).thenReturn(true);

        assertThatThrownBy(() -> service.update(ITEM_ID, updateDto, LOCALE))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("update throws when quantity is negative")
    void updateThrowsOnNegativeQuantity() {
        LabInventoryItemRequestDTO updateDto = new LabInventoryItemRequestDTO();
        updateDto.setQuantity(-1);
        updateDto.setReorderThreshold(5);

        when(inventoryRepository.findByIdAndActiveTrue(ITEM_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.update(ITEM_ID, updateDto, LOCALE))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── deactivate ───────────────────────────────────────────────

    @Test
    @DisplayName("deactivate sets active=false and saves")
    void deactivateSetsActiveFalse() {
        when(inventoryRepository.findByIdAndActiveTrue(ITEM_ID)).thenReturn(Optional.of(item));
        when(inventoryRepository.save(item)).thenReturn(item);

        service.deactivate(ITEM_ID, LOCALE);

        assertThat(item.isActive()).isFalse();
        verify(inventoryRepository).save(item);
    }

    @Test
    @DisplayName("deactivate throws when item not found")
    void deactivateThrowsWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(inventoryRepository.findByIdAndActiveTrue(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(unknownId, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getLowStockItems ─────────────────────────────────────────

    @Test
    @DisplayName("getLowStockItems returns items below threshold")
    void getLowStockItemsReturnsList() {
        when(inventoryRepository.findLowStockItems(HOSPITAL_ID)).thenReturn(List.of(item));
        when(mapper.toDto(item)).thenReturn(responseDto);

        List<LabInventoryItemResponseDTO> result = service.getLowStockItems(HOSPITAL_ID, LOCALE);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getLowStockItems returns empty when none below threshold")
    void getLowStockItemsReturnsEmpty() {
        when(inventoryRepository.findLowStockItems(HOSPITAL_ID)).thenReturn(List.of());

        List<LabInventoryItemResponseDTO> result = service.getLowStockItems(HOSPITAL_ID, LOCALE);

        assertThat(result).isEmpty();
    }

    // ── scope checks ─────────────────────────────────────────────

    @Test
    @DisplayName("superAdmin can access any hospital")
    void superAdminBypassesScopeCheck() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true)
            .build());

        UUID anyHospital = UUID.randomUUID();
        Hospital otherHospital = new Hospital();
        otherHospital.setId(anyHospital);

        LabInventoryItem otherItem = new LabInventoryItem();
        otherItem.setId(UUID.randomUUID());
        otherItem.setHospital(otherHospital);
        otherItem.setActive(true);

        when(inventoryRepository.findByIdAndActiveTrue(otherItem.getId()))
            .thenReturn(Optional.of(otherItem));
        when(mapper.toDto(otherItem)).thenReturn(responseDto);

        LabInventoryItemResponseDTO result = service.getById(otherItem.getId(), LOCALE);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("create denied when no hospital scope")
    void createDeniedWithoutScope() {
        HospitalContextHolder.setContext(HospitalContext.empty());
        UUID otherHospital = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(otherHospital, requestDto, LOCALE))
            .isInstanceOf(AccessDeniedException.class);

        verify(inventoryRepository, never()).save(any());
    }
}
