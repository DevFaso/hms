package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MedicationCatalogItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationCatalogItemServiceImplTest {

    @Mock private MedicationCatalogItemRepository catalogRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private MedicationCatalogItemMapper mapper;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private MedicationCatalogItemServiceImpl service;

    private UUID hospitalId;
    private UUID itemId;
    private Hospital hospital;
    private MedicationCatalogItem item;
    private MedicationCatalogItemRequestDTO requestDTO;
    private MedicationCatalogItemResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        itemId = UUID.randomUUID();

        hospital = new Hospital();
        ReflectionTestUtils.setField(hospital, "id", hospitalId);

        item = MedicationCatalogItem.builder()
                .nameFr("Paracétamol")
                .genericName("Paracetamol")
                .brandName("Doliprane")
                .form("Tablet")
                .strength("500")
                .strengthUnit("mg")
                .category("Analgesic")
                .active(true)
                .hospital(hospital)
                .build();
        ReflectionTestUtils.setField(item, "id", itemId);

        requestDTO = MedicationCatalogItemRequestDTO.builder()
                .nameFr("Paracétamol")
                .genericName("Paracetamol")
                .brandName("Doliprane")
                .form("Tablet")
                .strength("500")
                .strengthUnit("mg")
                .category("Analgesic")
                .hospitalId(hospitalId)
                .active(true)
                .build();

        responseDTO = new MedicationCatalogItemResponseDTO();
        responseDTO.setId(itemId);
        responseDTO.setNameFr("Paracétamol");
    }

    // ── create ──

    @Test
    void create_success() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(mapper.toEntity(requestDTO)).thenReturn(item);
        when(catalogRepository.save(any(MedicationCatalogItem.class))).thenReturn(item);
        when(mapper.toResponseDTO(item)).thenReturn(responseDTO);

        MedicationCatalogItemResponseDTO result = service.create(requestDTO);

        assertThat(result.getNameFr()).isEqualTo("Paracétamol");
        verify(catalogRepository).save(any(MedicationCatalogItem.class));
    }

    @Test
    void create_hospitalNotFound_throws() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getById ──

    @Test
    void getById_success() {
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.of(item));
        when(mapper.toResponseDTO(item)).thenReturn(responseDTO);

        MedicationCatalogItemResponseDTO result = service.getById(itemId, hospitalId);

        assertThat(result.getId()).isEqualTo(itemId);
    }

    @Test
    void getById_notFound_throws() {
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(itemId, hospitalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── listByHospital ──

    @Test
    void listByHospital_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MedicationCatalogItem> page = new PageImpl<>(List.of(item));
        when(catalogRepository.findByHospital_IdAndActiveTrue(hospitalId, pageable)).thenReturn(page);
        when(mapper.toResponseDTO(item)).thenReturn(responseDTO);

        Page<MedicationCatalogItemResponseDTO> result = service.listByHospital(hospitalId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ── search ──

    @Test
    void search_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MedicationCatalogItem> page = new PageImpl<>(List.of(item));
        when(catalogRepository.searchByHospital(hospitalId, "Para", pageable)).thenReturn(page);
        when(mapper.toResponseDTO(item)).thenReturn(responseDTO);

        Page<MedicationCatalogItemResponseDTO> result = service.search(hospitalId, "Para", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ── listByCategory ──

    @Test
    void listByCategory_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MedicationCatalogItem> page = new PageImpl<>(List.of(item));
        when(catalogRepository.findByHospital_IdAndCategoryAndActiveTrue(hospitalId, "Analgesic", pageable))
                .thenReturn(page);
        when(mapper.toResponseDTO(item)).thenReturn(responseDTO);

        Page<MedicationCatalogItemResponseDTO> result = service.listByCategory(hospitalId, "Analgesic", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ── update ──

    @Test
    void update_success() {
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.of(item));
        when(catalogRepository.save(any(MedicationCatalogItem.class))).thenReturn(item);
        when(mapper.toResponseDTO(item)).thenReturn(responseDTO);

        MedicationCatalogItemResponseDTO result = service.update(itemId, requestDTO);

        assertThat(result.getNameFr()).isEqualTo("Paracétamol");
        verify(catalogRepository).save(item);
    }

    @Test
    void update_notFound_throws() {
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(itemId, requestDTO))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── deactivate ──

    @Test
    void deactivate_success() {
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.of(item));
        when(catalogRepository.save(any(MedicationCatalogItem.class))).thenReturn(item);

        service.deactivate(itemId, hospitalId);

        assertThat(item.isActive()).isFalse();
        verify(catalogRepository).save(item);
    }

    @Test
    void deactivate_emitsMedicationDeactivatedAuditEvent() {
        // P-04: formulary deactivation must produce a distinct audit event so admin
        // actions are queryable independently of routine catalog edits.
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.of(item));
        when(catalogRepository.save(any(MedicationCatalogItem.class))).thenReturn(item);

        service.deactivate(itemId, hospitalId);

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        AuditEventRequestDTO logged = captor.getValue();
        assertThat(logged.getEventType()).isEqualTo(AuditEventType.MEDICATION_DEACTIVATED);
        assertThat(logged.getResourceId()).isEqualTo(itemId.toString());
        assertThat(logged.getEntityType()).isEqualTo("MEDICATION_CATALOG_ITEM");
    }

    @Test
    void deactivate_notFound_throws() {
        when(catalogRepository.findByIdAndHospital_Id(itemId, hospitalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(itemId, hospitalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
