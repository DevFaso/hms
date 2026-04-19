package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class StockTransactionMapper {

    public StockTransactionResponseDTO toResponseDTO(StockTransaction entity) {
        if (entity == null) {
            return null;
        }

        return StockTransactionResponseDTO.builder()
            .id(entity.getId())
            .inventoryItemId(entity.getInventoryItem() != null ? entity.getInventoryItem().getId() : null)
            .stockLotId(entity.getStockLot() != null ? entity.getStockLot().getId() : null)
            .transactionType(entity.getTransactionType() != null ? entity.getTransactionType().name() : null)
            .quantity(entity.getQuantity())
            .reason(entity.getReason())
            .referenceId(entity.getReferenceId())
            .performedBy(entity.getPerformedByUser() != null ? entity.getPerformedByUser().getId() : null)
            .createdAt(entity.getCreatedAt())
            .build();
    }

    public StockTransaction toEntity(StockTransactionRequestDTO dto,
                                     InventoryItem inventoryItem,
                                     StockLot stockLot,
                                     User performedByUser) {
        if (dto == null) {
            return null;
        }

        return StockTransaction.builder()
            .inventoryItem(inventoryItem)
            .stockLot(stockLot)
            .transactionType(StockTransactionType.valueOf(dto.getTransactionType()))
            .quantity(dto.getQuantity())
            .reason(dto.getReason())
            .referenceId(dto.getReferenceId())
            .performedByUser(performedByUser)
            .build();
    }
}
