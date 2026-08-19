package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import com.example.hms.service.pharmacy.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pharmacy/inventory")
@Tag(name = "Pharmacy Inventory", description = "Inventory item and stock lot management")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    // ── Inventory items ──────────────────────────────────────────────────

    @PostMapping("/items")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Create inventory item", description = "Register a new medication inventory item at a pharmacy")
    @ApiResponse(responseCode = "201", description = "Inventory item created")
    @ApiResponse(responseCode = "400", description = "Invalid request or duplicate item")
    public ResponseEntity<ApiResponseWrapper<InventoryItemResponseDTO>> createItem(
            @Valid @RequestBody InventoryItemRequestDTO dto) {
        InventoryItemResponseDTO created = inventoryService.createInventoryItem(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping("/items/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get inventory item", description = "Retrieve an inventory item by ID")
    @ApiResponse(responseCode = "200", description = "Inventory item found")
    @ApiResponse(responseCode = "404", description = "Inventory item not found")
    public ResponseEntity<ApiResponseWrapper<InventoryItemResponseDTO>> getItem(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(inventoryService.getInventoryItem(id)));
    }

    @GetMapping("/items")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List inventory items by hospital", description = "Paginated list of active inventory items for the current hospital")
    @ApiResponse(responseCode = "200", description = "Inventory items retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<InventoryItemResponseDTO>>> listByHospital(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(inventoryService.listByHospital(pageable)));
    }

    @GetMapping("/items/pharmacy/{pharmacyId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List inventory items by pharmacy", description = "Paginated list of active inventory items for a specific pharmacy")
    @ApiResponse(responseCode = "200", description = "Inventory items retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<InventoryItemResponseDTO>>> listByPharmacy(
            @PathVariable UUID pharmacyId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(inventoryService.listByPharmacy(pharmacyId, pageable)));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Update inventory item", description = "Update thresholds, unit, or active status")
    @ApiResponse(responseCode = "200", description = "Inventory item updated")
    @ApiResponse(responseCode = "404", description = "Inventory item not found")
    public ResponseEntity<ApiResponseWrapper<InventoryItemResponseDTO>> updateItem(
            @PathVariable UUID id,
            @Valid @RequestBody InventoryItemRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseWrapper.success(inventoryService.updateInventoryItem(id, dto)));
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Deactivate inventory item", description = "Soft-delete an inventory item")
    @ApiResponse(responseCode = "200", description = "Inventory item deactivated")
    @ApiResponse(responseCode = "404", description = "Inventory item not found")
    public ResponseEntity<ApiResponseWrapper<String>> deactivateItem(@PathVariable UUID id) {
        inventoryService.deactivateInventoryItem(id);
        return ResponseEntity.ok(ApiResponseWrapper.success("Inventory item deactivated"));
    }

    // ── Stock lots ───────────────────────────────────────────────────────

    @PostMapping("/lots")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Receive stock", description = "Record a new stock lot (goods receipt)")
    @ApiResponse(responseCode = "201", description = "Stock lot created and inventory updated")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    public ResponseEntity<ApiResponseWrapper<StockLotResponseDTO>> receiveStock(
            @Valid @RequestBody StockLotRequestDTO dto) {
        StockLotResponseDTO created = inventoryService.receiveStock(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping("/lots/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get stock lot", description = "Retrieve a stock lot by ID")
    @ApiResponse(responseCode = "200", description = "Stock lot found")
    @ApiResponse(responseCode = "404", description = "Stock lot not found")
    public ResponseEntity<ApiResponseWrapper<StockLotResponseDTO>> getLot(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(inventoryService.getStockLot(id)));
    }

    @GetMapping("/lots/item/{inventoryItemId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List lots by inventory item", description = "Paginated list of stock lots for a specific inventory item")
    @ApiResponse(responseCode = "200", description = "Stock lots retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<StockLotResponseDTO>>> listLotsByItem(
            @PathVariable UUID inventoryItemId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                inventoryService.listLotsByInventoryItem(inventoryItemId, pageable)));
    }

    @GetMapping("/lots/pharmacy/{pharmacyId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List lots by pharmacy", description = "Paginated list of all stock lots at a pharmacy")
    @ApiResponse(responseCode = "200", description = "Stock lots retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<StockLotResponseDTO>>> listLotsByPharmacy(
            @PathVariable UUID pharmacyId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                inventoryService.listLotsByPharmacy(pharmacyId, pageable)));
    }

    @GetMapping("/lots/expiring/{pharmacyId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Expiring lots", description = "List stock lots expiring within the given number of days")
    @ApiResponse(responseCode = "200", description = "Expiring lots retrieved")
    public ResponseEntity<ApiResponseWrapper<List<StockLotResponseDTO>>> getExpiringSoon(
            @PathVariable UUID pharmacyId,
            @RequestParam(defaultValue = "90") int daysAhead) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                inventoryService.getExpiringSoon(pharmacyId, daysAhead)));
    }

    @PutMapping("/lots/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Update stock lot", description = "Update lot metadata (notes, supplier, etc.)")
    @ApiResponse(responseCode = "200", description = "Stock lot updated")
    @ApiResponse(responseCode = "404", description = "Stock lot not found")
    public ResponseEntity<ApiResponseWrapper<StockLotResponseDTO>> updateLot(
            @PathVariable UUID id,
            @Valid @RequestBody StockLotRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseWrapper.success(inventoryService.updateStockLot(id, dto)));
    }

    // ── Reorder alerts ───────────────────────────────────────────────────

    @GetMapping("/reorder-alerts")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Items below reorder threshold", description = "List all inventory items below their reorder threshold for the hospital")
    @ApiResponse(responseCode = "200", description = "Low-stock items retrieved")
    public ResponseEntity<ApiResponseWrapper<List<InventoryItemResponseDTO>>> getReorderAlerts() {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                inventoryService.getItemsBelowReorderThresholdByHospital()));
    }

    @GetMapping("/reorder-alerts/pharmacy/{pharmacyId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Items below reorder threshold by pharmacy", description = "List inventory items below reorder threshold at a specific pharmacy")
    @ApiResponse(responseCode = "200", description = "Low-stock items retrieved")
    public ResponseEntity<ApiResponseWrapper<List<InventoryItemResponseDTO>>> getReorderAlertsByPharmacy(
            @PathVariable UUID pharmacyId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                inventoryService.getItemsBelowReorderThreshold(pharmacyId)));
    }

    @PostMapping("/reorder-alerts/trigger")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Trigger reorder alerts", description = "Send notifications for all items below reorder threshold")
    @ApiResponse(responseCode = "200", description = "Reorder alerts triggered")
    public ResponseEntity<ApiResponseWrapper<String>> triggerReorderAlerts() {
        inventoryService.triggerReorderAlerts();
        return ResponseEntity.ok(ApiResponseWrapper.success("Reorder alerts triggered"));
    }
}
