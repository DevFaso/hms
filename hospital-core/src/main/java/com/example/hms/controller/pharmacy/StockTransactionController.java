package com.example.hms.controller.pharmacy;

import com.example.hms.security.audit.WriteAudited;
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

import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@WriteAudited(skip = true, reason = "service emits STOCK_* events")
@RequestMapping("/pharmacy/stock-transactions")
@Tag(name = "Stock Transactions", description = "Record and query stock movements (adjustments, transfers, returns)")
@RequiredArgsConstructor
public class StockTransactionController {

    private final StockTransactionService stockTransactionService;
    /** QA D3415 (r70): the quarantine and its witnessed destruction. */
    private final com.example.hms.service.pharmacy.QuarantineDestructionService quarantineService;

    @GetMapping("/quarantine")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Quarantined stock",
            description = "QA D3415: units set aside (not for sale) per item and lot, waiting to be destroyed")
    @ApiResponse(responseCode = "200", description = "Quarantine returned")
    public ResponseEntity<ApiResponseWrapper<List<com.example.hms.payload.dto.pharmacy.QuarantineLineDTO>>> quarantine(
            @RequestParam UUID pharmacyId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(quarantineService.list(pharmacyId)));
    }

    @PostMapping("/quarantine/destructions")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'STORE_MANAGER', 'HOSPITAL_ADMIN')")
    @Operation(summary = "Destroy quarantined stock",
            description = "QA D3415: a DESTRUCTION of quarantined units, witnessed for a controlled drug")
    @ApiResponse(responseCode = "201", description = "Destruction recorded")
    @ApiResponse(responseCode = "400", description = "Missing field, more than the quarantine holds, or no witness")
    public ResponseEntity<ApiResponseWrapper<StockTransactionResponseDTO>> destroyQuarantined(
            @Valid @RequestBody com.example.hms.payload.dto.pharmacy.QuarantineDestructionRequestDTO dto) {
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(quarantineService.destroy(dto)));
    }

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

    @GetMapping("/witness-candidates")
    // QA D965: the dispensing page (pharmacy verifier) needs the witness of a controlled fill.
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List witness candidates",
            description = "Colleagues of the active hospital who may witness a controlled-substance movement")
    @ApiResponse(responseCode = "200", description = "Candidates retrieved")
    public ResponseEntity<ApiResponseWrapper<List<StockWitnessCandidateDTO>>> listWitnessCandidates() {
        return ResponseEntity.ok(ApiResponseWrapper.success(stockTransactionService.listWitnessCandidates()));
    }

    /**
     * QA D2679: the witness confirms, with their own password or authenticator code, that they are present for
     * the caller's controlled-drug movements. Open to whoever may record such a movement (a fill, a receipt, a
     * destruction, an adjustment); the witness's eligibility and factor are the service's checks.
     */
    @PostMapping("/witness-confirmations")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN')")
    @Operation(summary = "Confirm the witness of a controlled-drug movement",
            description = "The witness's own password, or the code of their authenticator when they have one. "
                    + "The confirmation stands for the caller's controlled movements at the active hospital for "
                    + "a few minutes; a controlled movement naming a witness who has not confirmed is refused.")
    @ApiResponse(responseCode = "201", description = "Witness confirmed")
    @ApiResponse(responseCode = "400", description = "Not an eligible witness, or the password or code was refused")
    public ResponseEntity<ApiResponseWrapper<com.example.hms.payload.dto.pharmacy.WitnessConfirmationResponseDTO>>
            confirmWitness(@Valid @RequestBody com.example.hms.payload.dto.pharmacy.WitnessConfirmationRequestDTO dto) {
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(stockTransactionService.confirmWitness(dto)));
    }

    @GetMapping("/returnable-transfers")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Paired transfers with stock available to return")
    public ResponseEntity<ApiResponseWrapper<List<com.example.hms.payload.dto.pharmacy.ReturnableTransferDTO>>>
            listReturnableTransfers(@RequestParam UUID inventoryItemId,
                                   @RequestParam(required = false) UUID stockLotId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(stockTransactionService.listReturnableTransfers(inventoryItemId, stockLotId)));
    }

    @GetMapping("/returnable-dispenses")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'INVENTORY_CLERK', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Fills a return can be booked against",
            description = "QA D932: the completed or partial fills of this item (and lot) with stock still out")
    @ApiResponse(responseCode = "200", description = "Fills retrieved")
    public ResponseEntity<ApiResponseWrapper<List<com.example.hms.payload.dto.pharmacy.ReturnableDispenseDTO>>>
            listReturnableDispenses(@RequestParam UUID inventoryItemId,
                                    @RequestParam(required = false) UUID stockLotId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockTransactionService.listReturnableDispenses(inventoryItemId, stockLotId)));
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
