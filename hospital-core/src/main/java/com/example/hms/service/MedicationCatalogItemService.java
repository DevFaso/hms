package com.example.hms.service;

import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface MedicationCatalogItemService {

    MedicationCatalogItemResponseDTO create(MedicationCatalogItemRequestDTO dto);

    MedicationCatalogItemResponseDTO getById(UUID id, UUID hospitalId);

    Page<MedicationCatalogItemResponseDTO> listByHospital(UUID hospitalId, Pageable pageable);

    Page<MedicationCatalogItemResponseDTO> search(UUID hospitalId, String query, Pageable pageable);

    Page<MedicationCatalogItemResponseDTO> listByCategory(UUID hospitalId, String category, Pageable pageable);

    MedicationCatalogItemResponseDTO update(UUID id, MedicationCatalogItemRequestDTO dto);

    void deactivate(UUID id, UUID hospitalId);
}
