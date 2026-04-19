package com.example.hms.mapper.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.MedicationCatalogItem;
import com.example.hms.payload.dto.pharmacy.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.MedicationCatalogItemResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class MedicationCatalogItemMapper {

    public MedicationCatalogItemResponseDTO toResponseDTO(MedicationCatalogItem entity) {
        if (entity == null) {
            return null;
        }

        return MedicationCatalogItemResponseDTO.builder()
            .id(entity.getId())
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .code(entity.getCode())
            .nameFr(entity.getNameFr())
            .genericName(entity.getGenericName())
            .atcCode(entity.getAtcCode())
            .form(entity.getForm())
            .strength(entity.getStrength())
            .unit(entity.getUnit())
            .rxnormCode(entity.getRxnormCode())
            .description(entity.getDescription())
            .active(entity.isActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public MedicationCatalogItem toEntity(MedicationCatalogItemRequestDTO dto, Hospital hospital) {
        if (dto == null) {
            return null;
        }

        return MedicationCatalogItem.builder()
            .hospital(hospital)
            .code(dto.getCode())
            .nameFr(dto.getNameFr())
            .genericName(dto.getGenericName())
            .atcCode(dto.getAtcCode())
            .form(dto.getForm())
            .strength(dto.getStrength())
            .unit(dto.getUnit())
            .rxnormCode(dto.getRxnormCode())
            .description(dto.getDescription())
            .active(dto.getActive() == null || dto.getActive())
            .build();
    }

    public void updateEntity(MedicationCatalogItem entity, MedicationCatalogItemRequestDTO dto) {
        if (dto.getCode() != null) {
            entity.setCode(dto.getCode());
        }
        if (dto.getNameFr() != null) {
            entity.setNameFr(dto.getNameFr());
        }
        if (dto.getGenericName() != null) {
            entity.setGenericName(dto.getGenericName());
        }
        if (dto.getAtcCode() != null) {
            entity.setAtcCode(dto.getAtcCode());
        }
        if (dto.getForm() != null) {
            entity.setForm(dto.getForm());
        }
        if (dto.getStrength() != null) {
            entity.setStrength(dto.getStrength());
        }
        if (dto.getUnit() != null) {
            entity.setUnit(dto.getUnit());
        }
        if (dto.getRxnormCode() != null) {
            entity.setRxnormCode(dto.getRxnormCode());
        }
        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
