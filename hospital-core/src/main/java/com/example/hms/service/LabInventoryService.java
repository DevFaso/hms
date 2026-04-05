package com.example.hms.service;

import com.example.hms.payload.dto.LabInventoryItemRequestDTO;
import com.example.hms.payload.dto.LabInventoryItemResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Manages lab inventory items – reagents, consumables, stock tracking.
 */
public interface LabInventoryService {

    Page<LabInventoryItemResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale);

    LabInventoryItemResponseDTO getById(UUID id, Locale locale);

    LabInventoryItemResponseDTO create(UUID hospitalId, LabInventoryItemRequestDTO dto, Locale locale);

    LabInventoryItemResponseDTO update(UUID id, LabInventoryItemRequestDTO dto, Locale locale);

    void deactivate(UUID id, Locale locale);

    List<LabInventoryItemResponseDTO> getLowStockItems(UUID hospitalId, Locale locale);
}
