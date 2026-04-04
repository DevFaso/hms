package com.example.hms.service;

import com.example.hms.enums.ValidationStudyType;
import com.example.hms.mapper.LabTestValidationStudyMapper;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestValidationStudy;
import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import com.example.hms.payload.dto.LabValidationSummaryDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabTestValidationStudyServiceImplTest {

    @Mock private LabTestValidationStudyRepository repository;
    @Mock private LabTestDefinitionRepository definitionRepository;
    @Mock private LabTestValidationStudyMapper mapper;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private LabTestValidationStudyServiceImpl service;

    private static final UUID DEF_ID   = UUID.randomUUID();
    private static final UUID STUDY_ID = UUID.randomUUID();

    // ── helpers ───────────────────────────────────────────────────────────────

    private LabTestDefinition definition() {
        return LabTestDefinition.builder()
                .testCode("CBC")
                .name("Complete Blood Count")
                .build();
    }

    private LabTestValidationStudyRequestDTO requestDto() {
        return LabTestValidationStudyRequestDTO.builder()
                .studyType(ValidationStudyType.PRECISION)
                .studyDate(LocalDate.of(2026, 4, 1))
                .passed(true)
                .summary("Meets CLSI EP05-A3 criteria")
                .build();
    }

    private LabTestValidationStudy studyEntity(LabTestDefinition def) {
        return LabTestValidationStudy.builder()
                .labTestDefinition(def)
                .studyType(ValidationStudyType.PRECISION)
                .studyDate(LocalDate.of(2026, 4, 1))
                .passed(true)
                .summary("Meets CLSI EP05-A3 criteria")
                .build();
    }

    private LabTestValidationStudyResponseDTO responseDto() {
        return LabTestValidationStudyResponseDTO.builder()
                .id(STUDY_ID)
                .labTestDefinitionId(DEF_ID)
                .testCode("CBC")
                .studyType(ValidationStudyType.PRECISION.name())
                .passed(true)
                .build();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_success() {
        LabTestDefinition def = definition();
        LabTestValidationStudy entity = studyEntity(def);
        LabTestValidationStudyResponseDTO resp = responseDto();

        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(def));
        when(mapper.toEntity(any(), any())).thenReturn(entity);
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toDto(entity)).thenReturn(resp);

        LabTestValidationStudyResponseDTO result = service.create(DEF_ID, requestDto());

        assertThat(result.getLabTestDefinitionId()).isEqualTo(DEF_ID);
        assertThat(result.isPassed()).isTrue();
        verify(repository).save(entity);
    }

    @Test
    void create_definitionNotFound_throwsEntityNotFound() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.empty());

        var req = requestDto();
        assertThatThrownBy(() -> service.create(DEF_ID, req))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Lab Test Definition not found");

        verify(repository, never()).save(any());
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_success() {
        LabTestValidationStudy entity = studyEntity(definition());
        LabTestValidationStudyResponseDTO resp = responseDto();

        when(repository.findById(STUDY_ID)).thenReturn(Optional.of(entity));
        when(mapper.toDto(entity)).thenReturn(resp);

        LabTestValidationStudyResponseDTO result = service.getById(STUDY_ID);

        assertThat(result.getId()).isEqualTo(STUDY_ID);
    }

    @Test
    void getById_notFound_throwsEntityNotFound() {
        when(repository.findById(STUDY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(STUDY_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Validation study not found");
    }

    // ── getByDefinitionId ─────────────────────────────────────────────────────

    @Test
    void getByDefinitionId_success() {
        LabTestDefinition def = definition();
        LabTestValidationStudy entity = studyEntity(def);
        LabTestValidationStudyResponseDTO resp = responseDto();

        when(definitionRepository.existsById(DEF_ID)).thenReturn(true);
        when(repository.findByLabTestDefinition_IdOrderByStudyDateDesc(DEF_ID))
                .thenReturn(List.of(entity));
        when(mapper.toDto(entity)).thenReturn(resp);

        List<LabTestValidationStudyResponseDTO> result = service.getByDefinitionId(DEF_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTestCode()).isEqualTo("CBC");
    }

    @Test
    void getByDefinitionId_definitionNotFound_throwsEntityNotFound() {
        when(definitionRepository.existsById(DEF_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getByDefinitionId(DEF_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Lab Test Definition not found");
    }

    @Test
    void getByDefinitionId_emptyList_returnsEmpty() {
        when(definitionRepository.existsById(DEF_ID)).thenReturn(true);
        when(repository.findByLabTestDefinition_IdOrderByStudyDateDesc(DEF_ID))
                .thenReturn(List.of());

        List<LabTestValidationStudyResponseDTO> result = service.getByDefinitionId(DEF_ID);

        assertThat(result).isEmpty();
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_success() {
        LabTestValidationStudy entity = studyEntity(definition());
        LabTestValidationStudyRequestDTO dto = LabTestValidationStudyRequestDTO.builder()
                .studyType(ValidationStudyType.ACCURACY)
                .studyDate(LocalDate.of(2026, 4, 2))
                .passed(false)
                .summary("Updated summary")
                .build();
        LabTestValidationStudyResponseDTO resp = LabTestValidationStudyResponseDTO.builder()
                .id(STUDY_ID)
                .studyType(ValidationStudyType.ACCURACY.name())
                .passed(false)
                .build();

        when(repository.findById(STUDY_ID)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toDto(entity)).thenReturn(resp);

        LabTestValidationStudyResponseDTO result = service.update(STUDY_ID, dto);

        verify(mapper).updateEntityFromDto(dto, entity);
        assertThat(result.getStudyType()).isEqualTo("ACCURACY");
    }

    @Test
    void update_notFound_throwsEntityNotFound() {
        when(repository.findById(STUDY_ID)).thenReturn(Optional.empty());

        var req = requestDto();
        assertThatThrownBy(() -> service.update(STUDY_ID, req))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Validation study not found");
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_success() {
        when(repository.existsById(STUDY_ID)).thenReturn(true);

        service.delete(STUDY_ID);

        verify(repository).deleteById(STUDY_ID);
    }

    @Test
    void delete_notFound_throwsEntityNotFound() {
        when(repository.existsById(STUDY_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(STUDY_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Validation study not found");

        verify(repository, never()).deleteById(any());
    }

    // ── getValidationSummary ─────────────────────────────────────────────────

    @Test
    void getValidationSummary_hospitalScoped_usesHospitalQuery() {
        UUID hospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        Object[] row = new Object[] {
            DEF_ID, "CBC", "CBC01", 10L, 8L, 2L, LocalDate.of(2026, 4, 1)
        };
        when(repository.findValidationSummaryByHospitalId(hospitalId))
                .thenReturn(List.<Object[]>of(row));

        List<LabValidationSummaryDTO> result = service.getValidationSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTestName()).isEqualTo("CBC");
        assertThat(result.get(0).getTotalStudies()).isEqualTo(10);
        assertThat(result.get(0).getPassedStudies()).isEqualTo(8);
        assertThat(result.get(0).getFailedStudies()).isEqualTo(2);
        assertThat(result.get(0).getPassRate()).isCloseTo(80.0, org.assertj.core.data.Offset.offset(0.01));
        verify(repository).findValidationSummaryByHospitalId(hospitalId);
        verify(repository, never()).findValidationSummaryAll();
    }

    @Test
    void getValidationSummary_superAdmin_usesAllQuery() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        Object[] row = new Object[] {
            DEF_ID, "HbA1c", "HBA1C", 5L, 5L, 0L, LocalDate.of(2026, 3, 15)
        };
        when(repository.findValidationSummaryAll())
                .thenReturn(List.<Object[]>of(row));

        List<LabValidationSummaryDTO> result = service.getValidationSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPassRate()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));
        verify(repository).findValidationSummaryAll();
        verify(repository, never()).findValidationSummaryByHospitalId(any());
    }

    @Test
    void getValidationSummary_emptyResults_returnsEmptyList() {
        UUID hospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(repository.findValidationSummaryByHospitalId(hospitalId))
                .thenReturn(List.of());

        List<LabValidationSummaryDTO> result = service.getValidationSummary();

        assertThat(result).isEmpty();
    }

    @Test
    void getValidationSummary_zeroTotal_passRateIsZero() {
        UUID hospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        Object[] row = new Object[] {
            DEF_ID, "TSH", "TSH01", 0L, 0L, 0L, null
        };
        when(repository.findValidationSummaryByHospitalId(hospitalId))
                .thenReturn(List.<Object[]>of(row));

        List<LabValidationSummaryDTO> result = service.getValidationSummary();

        assertThat(result.get(0).getPassRate()).isEqualTo(0.0);
        assertThat(result.get(0).getLastStudyDate()).isNull();
    }
}
