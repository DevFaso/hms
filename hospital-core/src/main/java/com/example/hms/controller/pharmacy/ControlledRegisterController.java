package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.ControlledRegisterDTO;
import com.example.hms.service.pharmacy.ControlledDrugRegisterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
 * QA D967: the controlled-drug (stupéfiants) register of a pharmacy - a read
 * view on the stock ledger, for the pharmacist, the store manager and the
 * hospital administrator, scoped to the caller's hospital.
 */
@RestController
@RequestMapping("/pharmacy/controlled-register")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Controlled-Drug Register", description = "Register of controlled-substance movements")
public class ControlledRegisterController {

    private static final String ROLES = "hasAnyRole('PHARMACIST', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')";

    private final ControlledDrugRegisterService registerService;

    @GetMapping
    @PreAuthorize(ROLES)
    @Operation(summary = "Controlled-drug register",
            description = "Every movement of the pharmacy's controlled drugs over the period, per drug, "
                    + "with the running balance, patient, prescriber, recorder and witness")
    @ApiResponse(responseCode = "200", description = "Register returned")
    public ResponseEntity<ApiResponseWrapper<ControlledRegisterDTO>> register(
            @RequestParam UUID pharmacyId,
            @RequestParam(required = false) UUID inventoryItemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                registerService.register(pharmacyId, inventoryItemId, from, to)));
    }

    @GetMapping("/export.csv")
    @PreAuthorize(ROLES)
    @Operation(summary = "Controlled-drug register as CSV")
    @ApiResponse(responseCode = "200", description = "CSV returned")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam UUID pharmacyId,
            @RequestParam(required = false) UUID inventoryItemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return file(registerService.exportCsv(pharmacyId, inventoryItemId, from, to),
                new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8),
                "registre-stupefiants-" + from + "-" + to + ".csv");
    }

    @GetMapping("/export.pdf")
    @PreAuthorize(ROLES)
    @Operation(summary = "Controlled-drug register as PDF")
    @ApiResponse(responseCode = "200", description = "PDF returned")
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam UUID pharmacyId,
            @RequestParam(required = false) UUID inventoryItemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return file(registerService.exportPdf(pharmacyId, inventoryItemId, from, to), MediaType.APPLICATION_PDF,
                "registre-stupefiants-" + from + "-" + to + ".pdf");
    }

    /** A download, never cached (patients' names). */
    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
