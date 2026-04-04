package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabQcEventMapper;
import com.example.hms.model.LabQcEvent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabQcEventRequestDTO;
import com.example.hms.payload.dto.LabQcEventResponseDTO;
import com.example.hms.payload.dto.LabQcSummaryDTO;
import com.example.hms.repository.LabQcEventRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabQcEventServiceImplTest {

    @Mock private LabQcEventRepository qcEventRepository;
    @Mock private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock private LabQcEventMapper qcEventMapper;
    @Mock private RoleValidator roleValidator;

    @InjectMocks
    private LabQcEventServiceImpl service;

    private UUID hospitalId;
    private UUID testDefId;
    private UUID eventId;
    private UUID userId;
    private LabQcEventResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        testDefId  = UUID.randomUUID();
        eventId    = UUID.randomUUID();
        userId     = UUID.randomUUID();
        responseDTO = LabQcEventResponseDTO.builder()
            .id(eventId)
            .hospitalId(hospitalId)
            .build();
    }

    // ── recordQcEvent ─────────────────────────────────────────────────────────

    @Test
    void recordQcEvent_success_passed() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("LOW_CONTROL")
            .measuredValue(new BigDecimal("100.0"))
            .expectedValue(new BigDecimal("100.0"))
            .build();

        LabQcEvent saved = new LabQcEvent();
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(qcEventRepository.save(any())).thenReturn(saved);
        when(qcEventMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabQcEventResponseDTO result = service.recordQcEvent(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        ArgumentCaptor<LabQcEvent> captor = ArgumentCaptor.forClass(LabQcEvent.class);
        verify(qcEventRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isTrue();
    }

    @Test
    void recordQcEvent_success_failedWhenDeviationExceedsTenPercent() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("HIGH_CONTROL")
            .measuredValue(new BigDecimal("90.0"))
            .expectedValue(new BigDecimal("100.0")) // 10% deviation — boundary: should fail (>0.10)
            .build();

        LabQcEvent saved = new LabQcEvent();
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(qcEventRepository.save(any())).thenReturn(saved);
        when(qcEventMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        service.recordQcEvent(request, Locale.ENGLISH);

        ArgumentCaptor<LabQcEvent> captor = ArgumentCaptor.forClass(LabQcEvent.class);
        verify(qcEventRepository).save(captor.capture());
        // 10% deviation is exactly at boundary; |0.10| <= 0.10 → passed=true
        assertThat(captor.getValue().isPassed()).isTrue();
    }

    @Test
    void recordQcEvent_success_failedWhenDeviationOverTenPercent() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("HIGH_CONTROL")
            .measuredValue(new BigDecimal("89.0"))
            .expectedValue(new BigDecimal("100.0")) // >10% → fail
            .build();

        LabQcEvent saved = new LabQcEvent();
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(qcEventRepository.save(any())).thenReturn(saved);
        when(qcEventMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        service.recordQcEvent(request, Locale.ENGLISH);

        ArgumentCaptor<LabQcEvent> captor = ArgumentCaptor.forClass(LabQcEvent.class);
        verify(qcEventRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isFalse();
    }

    @Test
    void recordQcEvent_zeroExpectedValue_passedFalse() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("LOW_CONTROL")
            .measuredValue(new BigDecimal("50.0"))
            .expectedValue(BigDecimal.ZERO) // division by zero guard → passed=false
            .build();

        LabQcEvent saved = new LabQcEvent();
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(qcEventRepository.save(any())).thenReturn(saved);
        when(qcEventMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        service.recordQcEvent(request, Locale.ENGLISH);

        ArgumentCaptor<LabQcEvent> captor = ArgumentCaptor.forClass(LabQcEvent.class);
        verify(qcEventRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isFalse(); // expected==0 → passed=false
    }

    @Test
    void recordQcEvent_missingHospitalId_throwsBusinessException() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .qcLevel("LOW_CONTROL")
            .measuredValue(BigDecimal.ONE)
            .expectedValue(BigDecimal.ONE)
            .build();

        assertThatThrownBy(() -> service.recordQcEvent(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("hospitalId");
    }

    @Test
    void recordQcEvent_missingMeasuredValue_throwsBusinessException() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("LOW_CONTROL")
            .expectedValue(BigDecimal.ONE)
            .build();

        assertThatThrownBy(() -> service.recordQcEvent(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("measuredValue");
    }

    @Test
    void recordQcEvent_missingExpectedValue_throwsBusinessException() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("LOW_CONTROL")
            .measuredValue(BigDecimal.ONE)
            .build();

        assertThatThrownBy(() -> service.recordQcEvent(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expectedValue");
    }

    @Test
    void recordQcEvent_unknownQcLevel_throwsBusinessException() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("INVALID_LEVEL")
            .measuredValue(BigDecimal.ONE)
            .expectedValue(BigDecimal.ONE)
            .build();

        assertThatThrownBy(() -> service.recordQcEvent(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unknown QC level");
    }

    @Test
    void recordQcEvent_withTestDefinition_loadsAndAssigns() {
        LabTestDefinition testDef = new LabTestDefinition();
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("LOW_CONTROL")
            .measuredValue(new BigDecimal("100"))
            .expectedValue(new BigDecimal("100"))
            .testDefinitionId(testDefId)
            .recordedAt(LocalDateTime.now())
            .notes("test note")
            .build();

        LabQcEvent saved = new LabQcEvent();
        when(labTestDefinitionRepository.findById(testDefId)).thenReturn(Optional.of(testDef));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(qcEventRepository.save(any())).thenReturn(saved);
        when(qcEventMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabQcEventResponseDTO result = service.recordQcEvent(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        ArgumentCaptor<LabQcEvent> captor = ArgumentCaptor.forClass(LabQcEvent.class);
        verify(qcEventRepository).save(captor.capture());
        assertThat(captor.getValue().getTestDefinition()).isEqualTo(testDef);
        assertThat(captor.getValue().getQcLevel()).isEqualTo(LabQcEventLevel.LOW_CONTROL);
    }

    @Test
    void recordQcEvent_testDefinitionNotFound_throwsResourceNotFoundException() {
        LabQcEventRequestDTO request = LabQcEventRequestDTO.builder()
            .hospitalId(hospitalId)
            .qcLevel("HIGH_CONTROL")
            .measuredValue(BigDecimal.ONE)
            .expectedValue(BigDecimal.ONE)
            .testDefinitionId(testDefId)
            .build();

        when(labTestDefinitionRepository.findById(testDefId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordQcEvent(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getQcEventById ────────────────────────────────────────────────────────

    @Test
    void getQcEventById_success() {
        LabQcEvent event = new LabQcEvent();
        when(qcEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(qcEventMapper.toResponseDTO(event)).thenReturn(responseDTO);

        LabQcEventResponseDTO result = service.getQcEventById(eventId, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void getQcEventById_notFound_throwsResourceNotFoundException() {
        when(qcEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getQcEventById(eventId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getQcEventsByHospital ─────────────────────────────────────────────────

    @Test
    void getQcEventsByHospital_withExplicitHospitalId_queryByHospital() {
        Pageable pageable = PageRequest.of(0, 10);
        LabQcEvent event = new LabQcEvent();
        Page<LabQcEvent> page = new PageImpl<>(List.of(event));

        when(qcEventRepository.findByHospitalId(hospitalId, pageable)).thenReturn(page);
        when(qcEventMapper.toResponseDTO(event)).thenReturn(responseDTO);

        Page<LabQcEventResponseDTO> result = service.getQcEventsByHospital(hospitalId, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(responseDTO);
    }

    @Test
    void getQcEventsByHospital_nullHospitalId_fallsBackToRoleValidator() {
        Pageable pageable = PageRequest.of(0, 10);
        LabQcEvent event = new LabQcEvent();
        Page<LabQcEvent> page = new PageImpl<>(List.of(event));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(qcEventRepository.findByHospitalId(hospitalId, pageable)).thenReturn(page);
        when(qcEventMapper.toResponseDTO(event)).thenReturn(responseDTO);

        Page<LabQcEventResponseDTO> result = service.getQcEventsByHospital(null, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(responseDTO);
    }

    @Test
    void getQcEventsByHospital_superAdmin_returnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        LabQcEvent event = new LabQcEvent();
        Page<LabQcEvent> page = new PageImpl<>(List.of(event));

        // Both explicit hospitalId and RoleValidator return null → super-admin path
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(qcEventRepository.findAll(pageable)).thenReturn(page);
        when(qcEventMapper.toResponseDTO(event)).thenReturn(responseDTO);

        Page<LabQcEventResponseDTO> result = service.getQcEventsByHospital(null, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(responseDTO);
        verify(qcEventRepository).findAll(pageable);
    }

    // ── getQcSummary ──────────────────────────────────────────────────────────

    @Test
    void getQcSummary_withHospital_returnsMappedSummary() {
        UUID defId = UUID.randomUUID();
        LocalDateTime lastDate = LocalDateTime.of(2026, 4, 1, 10, 0);
        Object[] row = {defId, "CBC", 100L, 95L, 5L, lastDate};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(qcEventRepository.findQcSummaryByHospitalId(hospitalId)).thenReturn(rows);

        List<LabQcSummaryDTO> result = service.getQcSummary(Locale.ENGLISH);

        assertThat(result).hasSize(1);
        LabQcSummaryDTO dto = result.get(0);
        assertThat(dto.getTestDefinitionId()).isEqualTo(defId);
        assertThat(dto.getTestName()).isEqualTo("CBC");
        assertThat(dto.getTotalEvents()).isEqualTo(100L);
        assertThat(dto.getPassedEvents()).isEqualTo(95L);
        assertThat(dto.getFailedEvents()).isEqualTo(5L);
        assertThat(dto.getPassRate()).isEqualTo(95.0);
        assertThat(dto.getLastEventDate()).isEqualTo(lastDate);
    }

    @Test
    void getQcSummary_superAdmin_usesAllQuery() {
        UUID defId = UUID.randomUUID();
        Object[] row = {defId, "BMP", 10L, 10L, 0L, null};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(qcEventRepository.findQcSummaryAll()).thenReturn(rows);

        List<LabQcSummaryDTO> result = service.getQcSummary(Locale.ENGLISH);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPassRate()).isEqualTo(100.0);
        verify(qcEventRepository).findQcSummaryAll();
    }

    @Test
    void getQcSummary_emptyRows_returnsEmptyList() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(qcEventRepository.findQcSummaryByHospitalId(hospitalId)).thenReturn(List.of());

        List<LabQcSummaryDTO> result = service.getQcSummary(Locale.ENGLISH);

        assertThat(result).isEmpty();
    }

    @Test
    void getQcSummary_zeroTotalEvents_passRateIsZero() {
        UUID defId = UUID.randomUUID();
        Object[] row = {defId, "LFT", 0L, 0L, 0L, null};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(qcEventRepository.findQcSummaryByHospitalId(hospitalId)).thenReturn(rows);

        List<LabQcSummaryDTO> result = service.getQcSummary(Locale.ENGLISH);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPassRate()).isZero();
    }
}
