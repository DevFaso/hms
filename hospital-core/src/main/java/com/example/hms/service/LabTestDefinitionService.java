package com.example.hms.service;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.LabTestDefinitionApprovalRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
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
    Page<LabTestDefinitionResponseDTO> search(String keyword, String unit, String category, Boolean active, LabTestDefinitionApprovalStatus approvalStatus, Pageable pageable);
    LabTestDefinitionResponseDTO processApprovalAction(UUID id, LabTestDefinitionApprovalRequestDTO dto);
    LabTestDefinitionResponseDTO updateReferenceRanges(UUID id, List<LabTestReferenceRangeDTO> ranges);

    /**
     * QA D1120: a new DRAFT version of an ACTIVE definition, linked to it. It
     * goes through the same approval and, on ACTIVATE, retires the version it
     * replaces.
     */
    LabTestDefinitionResponseDTO createNewVersion(UUID id);
    byte[] exportPdf();

    /**
     * Policy 2026-11-20 (provisional): the tests of the caller's catalogue the
     * curated mapping recommends a sensitive category for. Read-only: a
     * suggestion the lab director confirms.
     */
    List<com.example.hms.payload.dto.LabSensitivityRecommendationDTO> recommendedSensitivity();

    /**
     * Policy 2026-11-20: tag the tests the lab director confirmed, each with
     * the category the mapping recommends for it, each audited. A test the
     * mapping does not name, or a category it does not recommend, is refused
     * and nothing is applied.
     */
    List<com.example.hms.payload.dto.LabSensitivityRecommendationDTO> applyRecommendedSensitivity(
        List<com.example.hms.payload.dto.ApplyLabSensitivityRecommendationsRequestDTO.Item> confirmed);
}