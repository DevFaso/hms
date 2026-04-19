package com.example.hms.controller.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.service.pharmacy.StockTransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/pharmacy/stock-transactions")
@Tag(name = "Stock Transactions", description = "Record and query stock movements (adjustments, transfers, returns)")
@RequiredArgsConstructor
public class StockTransactionController {

    private final StockTransactionService stockTransactionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Record stock transaction", description = "Record a stock adjustment, transfer, or return")
    @ApiResponse(responseCode = "201", description = "Transaction recorded")
    @ApiResponse(responseCode = "400", description = "Invalid request or insufficient stock")
    public ResponseEntity<ApiResponseWrapper<StockTransactionResponseDTO>> recordTransaction(
            @Valid @RequestBody StockTransactionRequestDTO dto) {
        StockTransactionResponseDTO created = stockTransactionService.recordTransaction(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get transaction", description = "Retrieve a stock transaction by ID")
    @ApiResponse(responseCode = "200", description = "Transaction found")
    @ApiResponse(responseCode = "404", description = "Transaction not found")
    public ResponseEntity<ApiResponseWrapper<StockTransactionResponseDTO>> getTransaction(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(stockTransactionService.getTransaction(id)));
    }

    @GetMapping("/item/{inventoryItemId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List by inventory item", description = "Paginated transaction history for a specific inventory item")
    @ApiResponse(responseCode = "200", description = "Transactions retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<StockTransactionResponseDTO>>> listByItem(
            @PathVariable UUID inventoryItemId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockTransactionService.listByInventoryItem(inventoryItemId, pageable)));
    }

    @GetMapping("/pharmacy/{pharmacyId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List by pharmacy", description = "Paginated transaction history for a pharmacy")
    @ApiResponse(responseCode = "200", description = "Transactions retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<StockTransactionResponseDTO>>> listByPharmacy(
            @PathVariable UUID pharmacyId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockTransactionService.listByPharmacy(pharmacyId, pageable)));
    }

    @GetMapping("/pharmacy/{pharmacyId}/date-range")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List by pharmacy and date range", description = "Filtered transaction history by pharmacy and date range")
    @ApiResponse(responseCode = "200", description = "Transactions retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<StockTransactionResponseDTO>>> listByPharmacyAndDateRange(
            @PathVariable UUID pharmacyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockTransactionService.listByPharmacyAndDateRange(pharmacyId, from, to, pageable)));
    }

    @GetMapping("/pharmacy/{pharmacyId}/type/{type}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List by pharmacy and type", description = "Filtered transaction history by pharmacy and transaction type")
    @ApiResponse(responseCode = "200", description = "Transactions retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<StockTransactionResponseDTO>>> listByPharmacyAndType(
            @PathVariable UUID pharmacyId,
            @PathVariable StockTransactionType type,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockTransactionService.listByPharmacyAndType(pharmacyId, type, pageable)));
    }
}
