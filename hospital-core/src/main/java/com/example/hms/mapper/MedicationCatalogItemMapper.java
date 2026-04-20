package com.example.hms.mapper;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class MedicationCatalogItemMapper {

    public MedicationCatalogItemResponseDTO toResponseDTO(MedicationCatalogItem entity) {
        if (entity == null) return null;
        return MedicationCatalogItemResponseDTO.builder()
                .id(entity.getId())
                .nameFr(entity.getNameFr())
                .genericName(entity.getGenericName())
                .brandName(entity.getBrandName())
                .atcCode(entity.getAtcCode())
                .form(entity.getForm())
                .strength(entity.getStrength())
                .strengthUnit(entity.getStrengthUnit())
                .rxnormCode(entity.getRxnormCode())
                .route(entity.getRoute())
                .category(entity.getCategory())
                .essentialList(entity.isEssentialList())
                .controlled(entity.isControlled())
                .active(entity.isActive())
                .description(entity.getDescription())
                .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
                .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public MedicationCatalogItem toEntity(MedicationCatalogItemRequestDTO dto) {
        if (dto == null) return null;
        return MedicationCatalogItem.builder()
                .nameFr(dto.getNameFr())
                .genericName(dto.getGenericName())
                .brandName(dto.getBrandName())
                .atcCode(dto.getAtcCode())
                .form(dto.getForm())
                .strength(dto.getStrength())
                .strengthUnit(dto.getStrengthUnit())
                .rxnormCode(dto.getRxnormCode())
                .route(dto.getRoute())
                .category(dto.getCategory())
                .essentialList(dto.isEssentialList())
                .controlled(dto.isControlled())
                .active(dto.isActive())
                .description(dto.getDescription())
                .build();
    }
}
