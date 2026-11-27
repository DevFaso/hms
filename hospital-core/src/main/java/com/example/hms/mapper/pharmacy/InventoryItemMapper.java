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
            .controlled(med != null && med.isControlled())
            .quantityOnHand(entity.getQuantityOnHand())
            .stockRevision(entity.getStockRevision())
            .reorderThreshold(entity.getReorderThreshold())
            .reorderQuantity(entity.getReorderQuantity())
            .unitPrice(entity.getUnitPrice())
            .unit(entity.getUnit())
            .unitsPerPack(entity.getUnitsPerPack())
            .packUnit(entity.getPackUnit())
            .fullPacksOnHand(packsOf(entity))
            .looseUnitsOnHand(looseUnitsOf(entity))
            .active(entity.isActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /** QA D2674 (r65): the whole packs in the stock on hand, or null when the item has no pack size. */
    static BigDecimal packsOf(InventoryItem entity) {
        if (entity.getUnitsPerPack() == null || entity.getQuantityOnHand() == null) {
            return null;
        }
        return entity.getQuantityOnHand().divideToIntegralValue(BigDecimal.valueOf(entity.getUnitsPerPack()));
    }

    /** QA D2674: the units beyond the whole packs, or null when the item has no pack size. */
    static BigDecimal looseUnitsOf(InventoryItem entity) {
        if (entity.getUnitsPerPack() == null || entity.getQuantityOnHand() == null) {
            return null;
        }
        return entity.getQuantityOnHand().remainder(BigDecimal.valueOf(entity.getUnitsPerPack()));
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
            .unitPrice(dto.getUnitPrice())
            .active(dto.getActive() == null || dto.getActive())
            .build();
    }

    /**
     * Everything but the quantity on hand, which is never written by an
     * entity flush; {@code InventoryServiceImpl.updateInventoryItem} sets it
     * with an atomic UPDATE (#825 round 5).
     */
    public void updateEntity(InventoryItem entity, InventoryItemRequestDTO dto) {
        if (dto.getReorderThreshold() != null) {
            entity.setReorderThreshold(dto.getReorderThreshold());
        }
        if (dto.getReorderQuantity() != null) {
            entity.setReorderQuantity(dto.getReorderQuantity());
        }
        if (dto.getUnit() != null) {
            entity.setUnit(dto.getUnit());
        }
        if (dto.getUnitPrice() != null) {
            entity.setUnitPrice(dto.getUnitPrice());
        }
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
