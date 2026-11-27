package com.example.hms.service;

import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface MedicationCatalogItemService {

    /** Go-live D2 (V385): proposes the entry; it is in the catalogue once a second person approved it. */
    com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO create(
        MedicationCatalogItemRequestDTO dto);

    /** Go-live D2 (V385): submits an entry nobody approved yet, as it stands, for its approval. */
    MedicationCatalogItemResponseDTO requestFormularyApproval(UUID id, String reason);

    MedicationCatalogItemResponseDTO getById(UUID id, UUID hospitalId);

    Page<MedicationCatalogItemResponseDTO> listByHospital(UUID hospitalId, Pageable pageable);

    Page<MedicationCatalogItemResponseDTO> search(UUID hospitalId, String query, Pageable pageable);

    Page<MedicationCatalogItemResponseDTO> listByCategory(UUID hospitalId, String category, Pageable pageable);

    MedicationCatalogItemResponseDTO update(UUID id, MedicationCatalogItemRequestDTO dto);

    void deactivate(UUID id, UUID hospitalId);

    /**
     * Round 18 (QA D935): the hospital pharmacy sets, or approves, an item's
     * paediatric dose limits. Scoped to the caller's acting hospital; audited.
     */
    MedicationCatalogItemResponseDTO updatePediatricDosing(
        UUID id, com.example.hms.payload.dto.medication.PediatricDosingRequestDTO dto);
}
