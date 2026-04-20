package com.example.hms.mapper.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class InventoryItemMapper {

    public InventoryItemResponseDTO toResponseDTO(InventoryItem entity) {
        if (entity == null) {
            return null;
        }

        MedicationCatalogItem med = entity.getMedicationCatalogItem();

        return InventoryItemResponseDTO.builder()
            .id(entity.getId())
            .pharmacyId(entity.getPharmacy() != null ? entity.getPharmacy().getId() : null)
            .medicationCatalogItemId(med != null ? med.getId() : null)
            .medicationName(med != null ? med.getNameFr() : null)
            .medicationCode(med != null ? med.getCode() : null)
            .quantityOnHand(entity.getQuantityOnHand())
            .reorderThreshold(entity.getReorderThreshold())
            .reorderQuantity(entity.getReorderQuantity())
            .unit(entity.getUnit())
            .active(entity.isActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public InventoryItem toEntity(InventoryItemRequestDTO dto, Pharmacy pharmacy,
                                  MedicationCatalogItem medicationCatalogItem) {
        if (dto == null) {
            return null;
        }

        return InventoryItem.builder()
            .pharmacy(pharmacy)
            .medicationCatalogItem(medicationCatalogItem)
            .quantityOnHand(dto.getQuantityOnHand() != null ? dto.getQuantityOnHand() : BigDecimal.ZERO)
            .reorderThreshold(dto.getReorderThreshold() != null ? dto.getReorderThreshold() : BigDecimal.ZERO)
            .reorderQuantity(dto.getReorderQuantity() != null ? dto.getReorderQuantity() : BigDecimal.ZERO)
            .unit(dto.getUnit())
            .active(dto.getActive() == null || dto.getActive())
            .build();
    }

    public void updateEntity(InventoryItem entity, InventoryItemRequestDTO dto) {
        if (dto.getQuantityOnHand() != null) {
            entity.setQuantityOnHand(dto.getQuantityOnHand());
        }
        if (dto.getReorderThreshold() != null) {
            entity.setReorderThreshold(dto.getReorderThreshold());
        }
        if (dto.getReorderQuantity() != null) {
            entity.setReorderQuantity(dto.getReorderQuantity());
        }
        if (dto.getUnit() != null) {
            entity.setUnit(dto.getUnit());
        }
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
