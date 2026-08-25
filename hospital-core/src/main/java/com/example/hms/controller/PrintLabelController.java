package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.service.WristbandPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Wristband + specimen label PDFs (P3 #23b) — printing existed nowhere
 * while scanning did. Served as PDF blobs (the GET
 * /billing-invoices/{id}/pdf pattern); the portal opens them in a print
 * view.
 *
 * <p>Filter chain: the wristband GET rides the broad
 * {@code GET /patients/**} matcher (every role below is on its list); the
 * specimen-label GET has no matcher and falls to
 * {@code anyRequest().authenticated()}, so its annotation is the sole
 * gate.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Print Labels", description = "Wristband and specimen label PDFs")
public class PrintLabelController {

    private static final String WRISTBAND_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_DOCTOR','ROLE_SUPER_ADMIN')";

    private static final String SPECIMEN_ROLES =
        "hasAnyAuthority('ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER',"
            + "'ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    /**
     * Whoever handles stock: the pharmacist who dispenses against the label
     * and the storekeeper who prints it at goods-in. There is no
     * {@code /pharmacy/**} matcher in SecurityConfig, so — as with the
     * specimen label above — this annotation is the sole gate.
     *
     * <p>{@code hasAnyAuthority} with the ROLE_ prefix spelled out, matching
     * the two constants above rather than the {@code hasAnyRole} used inside
     * the pharmacy package; the two are equivalent, and consistency within
     * one file wins.
     */
    private static final String STOCK_LOT_ROLES =
        "hasAnyAuthority('ROLE_PHARMACIST','ROLE_PHARMACY_VERIFIER','ROLE_HOSPITAL_ADMIN',"
            + "'ROLE_SUPER_ADMIN')";

    private final WristbandPdfService wristbandPdfService;
    private final ControllerAuthUtils authUtils;

    @GetMapping("/patients/{patientId}/wristband.pdf")
    @PreAuthorize(WRISTBAND_ROLES)
    @Operation(summary = "Patient wristband PDF",
        description = "QR encodes the BARE patient UUID — the eMAR five-rights scan contract. "
            + "MRN (per the caller's hospital) is printed human-readable.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> wristband(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        byte[] pdf = wristbandPdfService.generateWristbandPdf(patientId, scope);
        return pdfResponse(pdf, "wristband-" + patientId + ".pdf");
    }

    @GetMapping("/lab-specimens/{specimenId}/label.pdf")
    @PreAuthorize(SPECIMEN_ROLES)
    @Operation(summary = "Specimen label PDF",
        description = "QR encodes the stored barcode_value (LAB-{accession}) — its first reader.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> specimenLabel(
        @PathVariable UUID specimenId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        byte[] pdf = wristbandPdfService.generateSpecimenLabelPdf(specimenId, scope);
        return pdfResponse(pdf, "specimen-label-" + specimenId + ".pdf");
    }

    @GetMapping("/pharmacy/stock-lots/{stockLotId}/label.pdf")
    @PreAuthorize(STOCK_LOT_ROLES)
    @Operation(summary = "Pharmacy stock-lot label PDF",
        description = "QR encodes the lot's barcode_value (LOT-{12 hex}) — the scan target for "
            + "dispense-time product verification. Mints the value on first print for lots "
            + "received before V138.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> stockLotLabel(
        @PathVariable UUID stockLotId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        byte[] pdf = wristbandPdfService.generateStockLotLabelPdf(stockLotId, scope);
        return pdfResponse(pdf, "stock-lot-label-" + stockLotId + ".pdf");
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
            .body(pdf);
    }
}
