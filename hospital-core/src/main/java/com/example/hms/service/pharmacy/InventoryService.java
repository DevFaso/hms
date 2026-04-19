package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InventoryService {

    // ── Inventory items ──────────────────────────────────────────────────
    InventoryItemResponseDTO createInventoryItem(InventoryItemRequestDTO dto);

    InventoryItemResponseDTO getInventoryItem(UUID id);

    Page<InventoryItemResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable);

    Page<InventoryItemResponseDTO> listByHospital(Pageable pageable);

    InventoryItemResponseDTO updateInventoryItem(UUID id, InventoryItemRequestDTO dto);

    void deactivateInventoryItem(UUID id);

    // ── Stock lots ───────────────────────────────────────────────────────
    StockLotResponseDTO receiveStock(StockLotRequestDTO dto);

    StockLotResponseDTO getStockLot(UUID id);

    Page<StockLotResponseDTO> listLotsByInventoryItem(UUID inventoryItemId, Pageable pageable);

    Page<StockLotResponseDTO> listLotsByPharmacy(UUID pharmacyId, Pageable pageable);

    List<StockLotResponseDTO> getExpiringSoon(UUID pharmacyId, int daysAhead);

    StockLotResponseDTO updateStockLot(UUID id, StockLotRequestDTO dto);

    // ── Reorder alerts ───────────────────────────────────────────────────
    List<InventoryItemResponseDTO> getItemsBelowReorderThreshold(UUID pharmacyId);

    List<InventoryItemResponseDTO> getItemsBelowReorderThresholdByHospital();

    void triggerReorderAlerts();
}
