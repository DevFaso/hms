package com.example.hms.controller;

import com.example.hms.enums.ValidationStudyType;
import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import com.example.hms.payload.dto.LabValidationSummaryDTO;
import com.example.hms.service.LabTestValidationStudyService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabTestValidationStudyControllerTest {

    @Mock  private LabTestValidationStudyService service;
    @InjectMocks private LabTestValidationStudyController controller;

    private static final UUID DEF_ID   = UUID.randomUUID();
    private static final UUID STUDY_ID = UUID.randomUUID();

    // ── helpers ───────────────────────────────────────────────────────────────

    private LabTestValidationStudyRequestDTO requestDto() {
        return LabTestValidationStudyRequestDTO.builder()
                .studyType(ValidationStudyType.PRECISION)
                .studyDate(LocalDate.of(2026, 4, 1))
                .passed(true)
                .build();
    }

    private LabTestValidationStudyResponseDTO responseDto() {
        return LabTestValidationStudyResponseDTO.builder()
                .id(STUDY_ID)
                .labTestDefinitionId(DEF_ID)
                .studyType(ValidationStudyType.PRECISION.name())
                .passed(true)
                .build();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_returns201() {
        when(service.create(eq(DEF_ID), any())).thenReturn(responseDto());

        ResponseEntity<?> result = controller.create(DEF_ID, requestDto());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void create_definitionNotFound_propagatesEntityNotFound() {
        when(service.create(eq(DEF_ID), any()))
                .thenThrow(new EntityNotFoundException("Lab Test Definition not found"));

        var req = requestDto();
        assertThatThrownBy(() -> controller.create(DEF_ID, req))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── getByDefinition ───────────────────────────────────────────────────────

    @Test
    void getByDefinition_returns200_withList() {
        when(service.getByDefinitionId(DEF_ID)).thenReturn(List.of(responseDto()));

        ResponseEntity<?> result = controller.getByDefinition(DEF_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void getByDefinition_emptyList_returns200() {
        when(service.getByDefinitionId(DEF_ID)).thenReturn(List.of());

        ResponseEntity<?> result = controller.getByDefinition(DEF_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_returns200() {
        when(service.getById(STUDY_ID)).thenReturn(responseDto());

        ResponseEntity<?> result = controller.getById(STUDY_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getById_notFound_propagatesEntityNotFound() {
        when(service.getById(STUDY_ID))
                .thenThrow(new EntityNotFoundException("Validation study not found"));

        assertThatThrownBy(() -> controller.getById(STUDY_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_returns200() {
        when(service.update(eq(STUDY_ID), any())).thenReturn(responseDto());

        ResponseEntity<?> result = controller.update(STUDY_ID, requestDto());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void update_notFound_propagatesEntityNotFound() {
        when(service.update(eq(STUDY_ID), any()))
                .thenThrow(new EntityNotFoundException("Validation study not found"));

        var req = requestDto();
        assertThatThrownBy(() -> controller.update(STUDY_ID, req))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_returns200() {
        doNothing().when(service).delete(STUDY_ID);

        ResponseEntity<?> result = controller.delete(STUDY_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).delete(STUDY_ID);
    }

    @Test
    void delete_notFound_propagatesEntityNotFound() {
        doThrow(new EntityNotFoundException("Validation study not found"))
                .when(service).delete(STUDY_ID);

        assertThatThrownBy(() -> controller.delete(STUDY_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    @Test
    void getSummary_returns200WithData() {
        LabValidationSummaryDTO summary = LabValidationSummaryDTO.builder()
                .testDefinitionId(DEF_ID)
                .testName("HbA1c")
                .testCode("HBA1C")
                .totalStudies(10L)
                .passedStudies(9L)
                .failedStudies(1L)
                .passRate(90.0)
                .build();
        when(service.getValidationSummary()).thenReturn(List.of(summary));

        ResponseEntity<?> result = controller.getSummary();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getSummary_emptyList_returns200() {
        when(service.getValidationSummary()).thenReturn(List.of());

        ResponseEntity<?> result = controller.getSummary();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
