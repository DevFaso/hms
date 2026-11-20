package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * QA D2674 (r65): stock kept in packs is counted from now on in units, {@code unitsPerPack} to a pack. Once per
     * item; the lots, the stock on hand, the thresholds and the prices are converted in one transaction, and an
     * ADJUSTMENT movement per lot keeps the ledger equal to the lot.
     */
    InventoryItemResponseDTO setPackSize(UUID id, com.example.hms.payload.dto.pharmacy.PackSizeRequestDTO dto);

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
