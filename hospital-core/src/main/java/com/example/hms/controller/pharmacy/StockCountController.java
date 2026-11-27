package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.pharmacy.*;
import com.example.hms.service.pharmacy.StockCountService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@com.example.hms.security.audit.WriteAudited(entity = "INVENTORY")
@RequestMapping("/pharmacy/stock-counts")
@RequiredArgsConstructor
public class StockCountController {
    private final StockCountService counts;
    private final com.example.hms.service.pharmacy.StockBalanceReconciliationService balanceReconciliation;
    private final com.example.hms.service.pharmacy.PharmacyConsumptionService consumption;

    @GetMapping("/consumption")
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','ACCOUNTANT','SUPER_ADMIN')")
    public PharmacyConsumptionDTO consumption(@RequestParam UUID pharmacyId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return consumption.report(pharmacyId, from, to);
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
    public StockCountResponseDTO record(@Valid @RequestBody StockCountRequestDTO request) {
        return counts.record(request);
    }

    @PostMapping("/items/{itemId}/reconcile-balance")
    @PreAuthorize("hasAnyRole('PHARMACIST','STORE_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
    public UUID reconcileBalance(@PathVariable UUID itemId, @Valid @RequestBody StockBalanceReconciliationRequest request) {
        return balanceReconciliation.reconcile(itemId, request);
    }

    @PostMapping("/{id}/confirm-adjustment")
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
    public StockCountResponseDTO confirm(@PathVariable UUID id, @Valid @RequestBody StockCountConfirmationDTO confirmation) {
        return counts.confirm(id, confirmation);
    }

    /**
     * QA D4183: the hospital's own stores a count, a balance reconciliation or the consumption report is read for.
     * The accountant reads the report and has no access to the pharmacy registry, so the list is served here.
     */
    @GetMapping("/stores")
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','ACCOUNTANT','SUPER_ADMIN')")
    public java.util.List<StockStoreDTO> stores() {
        return counts.stores();
    }

    /** QA D4191: closes a count that will never be applied; nothing moves in the stock. */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
    public StockCountResponseDTO close(@PathVariable UUID id, @Valid @RequestBody StockCountClosureDTO closure) {
        return counts.close(id, closure);
    }

    /** QA D4183: the items whose total no longer equals the sum of their lots, with the lots to count. */
    @GetMapping("/balance-drift")
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','ACCOUNTANT','SUPER_ADMIN')")
    public java.util.List<StockBalanceDriftDTO> balanceDrift(@RequestParam UUID pharmacyId) {
        return balanceReconciliation.drifted(pharmacyId);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','ACCOUNTANT','SUPER_ADMIN')")
    public Page<StockCountResponseDTO> list(@RequestParam UUID pharmacyId, Pageable pageable) {
        return counts.list(pharmacyId, pageable);
    }
}
