package com.example.hms.service;

import com.example.hms.enums.InstrumentStatus;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabInstrumentMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabInstrument;
import com.example.hms.payload.dto.LabInstrumentRequestDTO;
import com.example.hms.payload.dto.LabInstrumentResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabInstrumentRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabInstrumentServiceImplTest {

    @Mock private LabInstrumentRepository instrumentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private LabInstrumentMapper mapper;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private LabInstrumentServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID INSTRUMENT_ID = UUID.randomUUID();
    private static final UUID DEPARTMENT_ID = UUID.randomUUID();
    private static final Locale LOCALE = Locale.ENGLISH;

    private Hospital hospital;
    private LabInstrument instrument;
    private LabInstrumentRequestDTO requestDto;
    private LabInstrumentResponseDTO responseDto;

    @BeforeEach
    void setUp() {
        // Set hospital context so scope checks pass
        HospitalContext context = HospitalContext.builder()
            .permittedHospitalIds(Set.of(HOSPITAL_ID))
            .activeHospitalId(HOSPITAL_ID)
            .superAdmin(false)
            .build();
        HospitalContextHolder.setContext(context);

        hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);

        instrument = new LabInstrument();
        instrument.setId(INSTRUMENT_ID);
        instrument.setName("Analyzer X100");
        instrument.setSerialNumber("SN-001");
        instrument.setHospital(hospital);
        instrument.setStatus(InstrumentStatus.ACTIVE);
        instrument.setActive(true);

        requestDto = new LabInstrumentRequestDTO();
        requestDto.setName("Analyzer X100");
        requestDto.setSerialNumber("SN-001");

        responseDto = new LabInstrumentResponseDTO();
        responseDto.setId(INSTRUMENT_ID.toString());
        responseDto.setName("Analyzer X100");

        // Default message source behavior
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    // ── getByHospital ────────────────────────────────────────────

    @Test
    @DisplayName("getByHospital returns paginated instruments")
    void getByHospitalReturnsPaginatedResults() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<LabInstrument> page = new PageImpl<>(List.of(instrument));
        when(instrumentRepository.findByHospitalIdAndActiveTrue(HOSPITAL_ID, pageable)).thenReturn(page);
        when(mapper.toDto(instrument)).thenReturn(responseDto);

        Page<LabInstrumentResponseDTO> result = service.getByHospital(HOSPITAL_ID, pageable, LOCALE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Analyzer X100");
    }

    @Test
    @DisplayName("getByHospital throws AccessDeniedException when scope mismatch")
    void getByHospitalDeniedForWrongScope() {
        UUID otherHospital = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getByHospital(otherHospital, pageable, LOCALE))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── getById ──────────────────────────────────────────────────

    @Test
    @DisplayName("getById returns instrument when found")
    void getByIdReturnsInstrument() {
        when(instrumentRepository.findByIdAndActiveTrue(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(mapper.toDto(instrument)).thenReturn(responseDto);

        LabInstrumentResponseDTO result = service.getById(INSTRUMENT_ID, LOCALE);

        assertThat(result.getId()).isEqualTo(INSTRUMENT_ID.toString());
    }

    @Test
    @DisplayName("getById throws ResourceNotFoundException when not found")
    void getByIdThrowsWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(instrumentRepository.findByIdAndActiveTrue(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(unknownId, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── create ───────────────────────────────────────────────────

    @Test
    @DisplayName("create saves and returns new instrument")
    void createSavesInstrument() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(instrumentRepository.existsByHospitalIdAndSerialNumber(HOSPITAL_ID, "SN-001")).thenReturn(false);
        when(mapper.toEntity(eq(requestDto), eq(hospital), any())).thenReturn(instrument);
        when(instrumentRepository.save(instrument)).thenReturn(instrument);
        when(mapper.toDto(instrument)).thenReturn(responseDto);

        LabInstrumentResponseDTO result = service.create(HOSPITAL_ID, requestDto, LOCALE);

        assertThat(result.getName()).isEqualTo("Analyzer X100");
        verify(instrumentRepository).save(instrument);
    }

    @Test
    @DisplayName("create throws when serial number duplicated")
    void createThrowsOnDuplicateSerial() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(instrumentRepository.existsByHospitalIdAndSerialNumber(HOSPITAL_ID, "SN-001")).thenReturn(true);

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
    @DisplayName("create resolves department when provided")
    void createResolvesDepartment() {
        requestDto.setDepartmentId(DEPARTMENT_ID.toString());
        Department dept = new Department();
        dept.setId(DEPARTMENT_ID);

        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
        when(instrumentRepository.existsByHospitalIdAndSerialNumber(HOSPITAL_ID, "SN-001")).thenReturn(false);
        when(departmentRepository.findById(DEPARTMENT_ID)).thenReturn(Optional.of(dept));
        when(mapper.toEntity(requestDto, hospital, dept)).thenReturn(instrument);
        when(instrumentRepository.save(instrument)).thenReturn(instrument);
        when(mapper.toDto(instrument)).thenReturn(responseDto);

        LabInstrumentResponseDTO result = service.create(HOSPITAL_ID, requestDto, LOCALE);

        assertThat(result).isNotNull();
        verify(departmentRepository).findById(DEPARTMENT_ID);
    }

    // ── update ───────────────────────────────────────────────────

    @Test
    @DisplayName("update modifies and returns instrument")
    void updateModifiesInstrument() {
        when(instrumentRepository.findByIdAndActiveTrue(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(instrumentRepository.save(instrument)).thenReturn(instrument);
        when(mapper.toDto(instrument)).thenReturn(responseDto);

        LabInstrumentResponseDTO result = service.update(INSTRUMENT_ID, requestDto, LOCALE);

        assertThat(result).isNotNull();
        verify(mapper).updateEntity(eq(instrument), eq(requestDto), any());
    }

    @Test
    @DisplayName("update throws when serial changes to existing value")
    void updateThrowsOnDuplicateSerial() {
        LabInstrumentRequestDTO updateDto = new LabInstrumentRequestDTO();
        updateDto.setSerialNumber("SN-NEW");

        when(instrumentRepository.findByIdAndActiveTrue(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(instrumentRepository.existsByHospitalIdAndSerialNumber(HOSPITAL_ID, "SN-NEW")).thenReturn(true);

        assertThatThrownBy(() -> service.update(INSTRUMENT_ID, updateDto, LOCALE))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── deactivate ───────────────────────────────────────────────

    @Test
    @DisplayName("deactivate sets active=false and saves")
    void deactivateSetsActiveFalse() {
        when(instrumentRepository.findByIdAndActiveTrue(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(instrumentRepository.save(instrument)).thenReturn(instrument);

        service.deactivate(INSTRUMENT_ID, LOCALE);

        assertThat(instrument.isActive()).isFalse();
        verify(instrumentRepository).save(instrument);
    }

    @Test
    @DisplayName("deactivate throws when instrument not found")
    void deactivateThrowsWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(instrumentRepository.findByIdAndActiveTrue(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(unknownId, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
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

        LabInstrument otherInstrument = new LabInstrument();
        otherInstrument.setId(UUID.randomUUID());
        otherInstrument.setHospital(otherHospital);
        otherInstrument.setActive(true);

        when(instrumentRepository.findByIdAndActiveTrue(otherInstrument.getId()))
            .thenReturn(Optional.of(otherInstrument));
        when(mapper.toDto(otherInstrument)).thenReturn(responseDto);

        LabInstrumentResponseDTO result = service.getById(otherInstrument.getId(), LOCALE);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("create denied when no hospital scope")
    void createDeniedWithoutScope() {
        HospitalContextHolder.setContext(HospitalContext.empty());
        UUID otherHospital = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(otherHospital, requestDto, LOCALE))
            .isInstanceOf(AccessDeniedException.class);

        verify(instrumentRepository, never()).save(any());
    }
}
