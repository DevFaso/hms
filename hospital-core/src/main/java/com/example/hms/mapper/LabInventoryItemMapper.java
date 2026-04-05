package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.LabInventoryItem;
import com.example.hms.payload.dto.LabInventoryItemRequestDTO;
import com.example.hms.payload.dto.LabInventoryItemResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class LabInventoryItemMapper {

    public LabInventoryItemResponseDTO toDto(LabInventoryItem entity) {
        if (entity == null) return null;

        boolean lowStock = entity.getQuantity() <= entity.getReorderThreshold();
        boolean expired = entity.getExpirationDate() != null
            && !entity.getExpirationDate().isAfter(LocalDate.now());

        return LabInventoryItemResponseDTO.builder()
            .id(entity.getId() != null ? entity.getId().toString() : null)
            .name(entity.getName())
            .itemCode(entity.getItemCode())
            .category(entity.getCategory())
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId().toString() : null)
            .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
            .quantity(entity.getQuantity())
            .unit(entity.getUnit())
            .reorderThreshold(entity.getReorderThreshold())
            .lowStock(lowStock)
            .supplier(entity.getSupplier())
            .lotNumber(entity.getLotNumber())
            .expirationDate(entity.getExpirationDate())
            .expired(expired)
            .notes(entity.getNotes())
            .active(entity.isActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public LabInventoryItem toEntity(LabInventoryItemRequestDTO dto, Hospital hospital) {
        if (dto == null) return null;

        return LabInventoryItem.builder()
            .name(dto.getName())
            .itemCode(dto.getItemCode())
            .category(dto.getCategory())
            .hospital(hospital)
            .quantity(dto.getQuantity() != null ? dto.getQuantity() : 0)
            .unit(dto.getUnit())
            .reorderThreshold(dto.getReorderThreshold() != null ? dto.getReorderThreshold() : 0)
            .supplier(dto.getSupplier())
            .lotNumber(dto.getLotNumber())
            .expirationDate(dto.getExpirationDate())
            .notes(dto.getNotes())
            .build();
    }

    public void updateEntity(LabInventoryItem entity, LabInventoryItemRequestDTO dto) {
        if (dto.getName() != null) entity.setName(dto.getName());
        if (dto.getItemCode() != null) entity.setItemCode(dto.getItemCode());
        if (dto.getCategory() != null) entity.setCategory(dto.getCategory());
        if (dto.getQuantity() != null) entity.setQuantity(dto.getQuantity());
        if (dto.getUnit() != null) entity.setUnit(dto.getUnit());
        if (dto.getReorderThreshold() != null) entity.setReorderThreshold(dto.getReorderThreshold());
        if (dto.getSupplier() != null) entity.setSupplier(dto.getSupplier());
        if (dto.getLotNumber() != null) entity.setLotNumber(dto.getLotNumber());
        if (dto.getExpirationDate() != null) entity.setExpirationDate(dto.getExpirationDate());
        if (dto.getNotes() != null) entity.setNotes(dto.getNotes());
    }
}
