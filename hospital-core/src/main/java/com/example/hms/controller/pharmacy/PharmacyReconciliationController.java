package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO;
import com.example.hms.service.pharmacy.PharmacyReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * QA D2673 (r65, billing-money plan PR 13): a pharmacy's daily reconciliation, money against stock, and its export.
 * The service checks the role on the assignment at the acting hospital and scopes the pharmacy to it.
 */
@RestController
@RequestMapping("/pharmacy/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Reconciliation", description = "Daily pharmacy reconciliation (scorecard M4)")
@SecurityRequirement(name = "bearerAuth")
public class PharmacyReconciliationController {

    private static final String READERS = "hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN', 'ACCOUNTANT')";

    private final PharmacyReconciliationService reconciliationService;

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "One pharmacy's day: sales, fills, payments, refunds and stock movements, with differences")
    public ResponseEntity<ApiResponseWrapper<PharmacyReconciliationDTO>> daily(
            @RequestParam UUID pharmacyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponseWrapper.success(reconciliationService.daily(pharmacyId, date)));
    }

    @GetMapping("/pharmacies")
    @PreAuthorize(READERS)
    @Operation(summary = "The pharmacies of the acting hospital the reader may reconcile")
    public ResponseEntity<ApiResponseWrapper<java.util.List<PharmacyReconciliationDTO>>> pharmacies() {
        return ResponseEntity.ok(ApiResponseWrapper.success(reconciliationService.pharmacies()));
    }

    @GetMapping("/export")
    @PreAuthorize(READERS)
    @Operation(summary = "The day's reconciliation as a spreadsheet file")
    public ResponseEntity<byte[]> export(
            @RequestParam UUID pharmacyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = reconciliationService.dayOf(date);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(PharmacyReconciliationService.CSV_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(PharmacyReconciliationService.csvFileName(day)).build().toString())
                .body(reconciliationService.exportCsv(pharmacyId, day));
    }
}
