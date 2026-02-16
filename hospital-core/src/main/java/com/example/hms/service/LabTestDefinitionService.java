package com.example.hms.service;

import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface LabTestDefinitionService {
    LabTestDefinitionResponseDTO create(LabTestDefinitionRequestDTO dto);
    LabTestDefinitionResponseDTO getById(UUID id);
    List<LabTestDefinitionResponseDTO> getAll();
    List<LabTestDefinitionResponseDTO> getActiveByHospital(UUID hospitalId);
    LabTestDefinitionResponseDTO update(UUID id, LabTestDefinitionRequestDTO dto);
    void delete(UUID id);
    Page<LabTestDefinitionResponseDTO> search(String keyword, String unit, String category, Boolean active, Pageable pageable);
}