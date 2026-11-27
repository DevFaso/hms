package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabInventoryItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabInventoryItem;
import com.example.hms.payload.dto.LabInventoryItemRequestDTO;
import com.example.hms.payload.dto.LabInventoryItemResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabInventoryItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabInventoryServiceImpl implements LabInventoryService {

    private static final String INVENTORY_NOT_FOUND_KEY = "inventory.notFound";

    private final LabInventoryItemRepository inventoryRepository;
    private final HospitalRepository hospitalRepository;
    private final LabInventoryItemMapper mapper;

    @Override
    public Page<LabInventoryItemResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        LabFacilityAccess.require(hospitalId, false);
        return inventoryRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable)
            .map(mapper::toDto);
    }

    @Override
    public Page<LabInventoryItemResponseDTO> searchByHospital(UUID hospitalId, String search, Pageable pageable, Locale locale) {
        LabFacilityAccess.require(hospitalId, false);
        if (search == null || search.isBlank()) return inventoryRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable).map(mapper::toDto);
        return inventoryRepository.searchActive(hospitalId, search.trim(), pageable).map(mapper::toDto);
    }

    @Override
    public LabInventoryItemResponseDTO getById(UUID id, Locale locale) {
        LabInventoryItem item = inventoryRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVENTORY_NOT_FOUND_KEY, id));
        LabFacilityAccess.require(item.getHospital().getId(), false);
        return mapper.toDto(item);
    }

    @Override
    @Transactional
    public LabInventoryItemResponseDTO create(UUID hospitalId, LabInventoryItemRequestDTO dto, Locale locale) {
        LabFacilityAccess.require(hospitalId, true);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));

        if (inventoryRepository.existsByHospitalIdAndItemCode(hospitalId, dto.getItemCode())) {
            throw BusinessRuleException.ofKey("inventory.duplicate.code", dto.getItemCode());
        }

        if (dto.getQuantity() != null && dto.getQuantity() < 0) {
            throw BusinessRuleException.ofKey("inventory.quantity.negative");
        }

        LabInventoryItem item = mapper.toEntity(dto, hospital);
        item = inventoryRepository.save(item);
        return mapper.toDto(item);
    }

    @Override
    @Transactional
    public LabInventoryItemResponseDTO update(UUID id, LabInventoryItemRequestDTO dto, Locale locale) {
        LabInventoryItem item = inventoryRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVENTORY_NOT_FOUND_KEY, id));
        LabFacilityAccess.require(item.getHospital().getId(), true);

        // Check item code uniqueness if changed
        if (dto.getItemCode() != null
                && !dto.getItemCode().equals(item.getItemCode())
                && inventoryRepository.existsByHospitalIdAndItemCode(
                    item.getHospital().getId(), dto.getItemCode())) {
            throw BusinessRuleException.ofKey("inventory.duplicate.code", dto.getItemCode());
        }

        if (dto.getQuantity() != null && dto.getQuantity() < 0) {
            throw BusinessRuleException.ofKey("inventory.quantity.negative");
        }

        mapper.updateEntity(item, dto);
        item = inventoryRepository.save(item);
        return mapper.toDto(item);
    }

    @Override
    @Transactional
    public void deactivate(UUID id, Locale locale) {
        LabInventoryItem item = inventoryRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVENTORY_NOT_FOUND_KEY, id));
        LabFacilityAccess.require(item.getHospital().getId(), true);
        item.setActive(false);
        inventoryRepository.save(item);
    }

    @Override
    public List<LabInventoryItemResponseDTO> getLowStockItems(UUID hospitalId, Locale locale) {
        LabFacilityAccess.require(hospitalId, true);
        return inventoryRepository.findLowStockItems(hospitalId).stream()
            .map(mapper::toDto)
            .toList();
    }

    // ── helpers ───────────────────────────────────────────────────

}
