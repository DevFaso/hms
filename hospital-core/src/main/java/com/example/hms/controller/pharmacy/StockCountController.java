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

    @PostMapping("/{id}/confirm-adjustment")
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
    public StockCountResponseDTO confirm(@PathVariable UUID id, @Valid @RequestBody StockCountConfirmationDTO confirmation) {
        return counts.confirm(id, confirmation);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACIST','INVENTORY_CLERK','STORE_MANAGER','HOSPITAL_ADMIN','ACCOUNTANT','SUPER_ADMIN')")
    public Page<StockCountResponseDTO> list(@RequestParam UUID pharmacyId, Pageable pageable) {
        return counts.list(pharmacyId, pageable);
    }
}
