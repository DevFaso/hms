package com.example.hms.service;

import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import com.example.hms.payload.dto.LabValidationSummaryDTO;

import java.util.List;
import java.util.UUID;

public interface LabTestValidationStudyService {

    LabTestValidationStudyResponseDTO create(UUID definitionId, LabTestValidationStudyRequestDTO dto);

    LabTestValidationStudyResponseDTO getById(UUID id);

    List<LabTestValidationStudyResponseDTO> getByDefinitionId(UUID definitionId);

    LabTestValidationStudyResponseDTO update(UUID id, LabTestValidationStudyRequestDTO dto);

    void delete(UUID id);

    /** Aggregated validation study summary per test definition, scoped to caller's hospital. */
    List<LabValidationSummaryDTO> getValidationSummary();
}
