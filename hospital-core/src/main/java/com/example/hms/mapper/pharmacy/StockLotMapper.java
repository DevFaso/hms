package com.example.hms.mapper.pharmacy;

import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class StockLotMapper {

    public StockLotResponseDTO toResponseDTO(StockLot entity) {
        if (entity == null) {
            return null;
        }

        return StockLotResponseDTO.builder()
            .id(entity.getId())
            .inventoryItemId(entity.getInventoryItem() != null ? entity.getInventoryItem().getId() : null)
            .lotNumber(entity.getLotNumber())
            .expiryDate(entity.getExpiryDate())
            .initialQuantity(entity.getInitialQuantity())
            .remainingQuantity(entity.getRemainingQuantity())
            .supplier(entity.getSupplier())
            .unitCost(entity.getUnitCost())
            .receivedDate(entity.getReceivedDate())
            .receivedBy(entity.getReceivedByUser() != null ? entity.getReceivedByUser().getId() : null)
            .notes(entity.getNotes())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public StockLot toEntity(StockLotRequestDTO dto, InventoryItem inventoryItem, User receivedByUser) {
        if (dto == null) {
            return null;
        }

        return StockLot.builder()
            .inventoryItem(inventoryItem)
            .lotNumber(dto.getLotNumber())
            .expiryDate(dto.getExpiryDate())
            .initialQuantity(dto.getInitialQuantity())
            .remainingQuantity(dto.getRemainingQuantity() != null
                ? dto.getRemainingQuantity() : dto.getInitialQuantity())
            .supplier(dto.getSupplier())
            .unitCost(dto.getUnitCost())
            .receivedDate(dto.getReceivedDate())
            .receivedByUser(receivedByUser)
            .notes(dto.getNotes())
            .build();
    }

    public void updateEntity(StockLot entity, StockLotRequestDTO dto) {
        if (dto.getLotNumber() != null) {
            entity.setLotNumber(dto.getLotNumber());
        }
        if (dto.getExpiryDate() != null) {
            entity.setExpiryDate(dto.getExpiryDate());
        }
        if (dto.getRemainingQuantity() != null) {
            entity.setRemainingQuantity(dto.getRemainingQuantity());
        }
        if (dto.getSupplier() != null) {
            entity.setSupplier(dto.getSupplier());
        }
        if (dto.getUnitCost() != null) {
            entity.setUnitCost(dto.getUnitCost());
        }
        if (dto.getNotes() != null) {
            entity.setNotes(dto.getNotes());
        }
    }
}
