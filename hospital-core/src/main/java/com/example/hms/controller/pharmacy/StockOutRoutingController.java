package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.PartnerNoShowRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockCheckResultDTO;
import com.example.hms.service.pharmacy.StockOutRoutingService;
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

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/pharmacy/routing")
@Tag(name = "Pharmacy Stock-Out Routing", description = "Stock-out routing and cross-tier handoff")
@RequiredArgsConstructor
public class StockOutRoutingController {

    private final StockOutRoutingService stockOutRoutingService;

    @GetMapping("/stock-check/{prescriptionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Check stock for prescription",
            description = "Check stock availability and list partner pharmacy options if insufficient")
    @ApiResponse(responseCode = "200", description = "Stock check completed")
    public ResponseEntity<ApiResponseWrapper<StockCheckResultDTO>> checkStock(
            @PathVariable UUID prescriptionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockOutRoutingService.checkStock(prescriptionId)));
    }

    @PostMapping("/route-to-partner")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Route prescription to partner pharmacy",
            description = "Route a prescription to an external partner pharmacy for fulfillment")
    @ApiResponse(responseCode = "201", description = "Prescription routed to partner")
    @ApiResponse(responseCode = "400", description = "Invalid request or prescription not routable")
    public ResponseEntity<ApiResponseWrapper<RoutingDecisionResponseDTO>> routeToPartner(
            @Valid @RequestBody RoutingDecisionRequestDTO dto) {
        RoutingDecisionResponseDTO result = stockOutRoutingService.routeToPartner(dto.getPrescriptionId(), dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(result));
    }

    @PostMapping("/print-for-patient/{prescriptionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Print prescription for patient",
            description = "Mark prescription as printed for patient to take to an external pharmacy (Tier 3)")
    @ApiResponse(responseCode = "201", description = "Prescription marked as printed")
    public ResponseEntity<ApiResponseWrapper<RoutingDecisionResponseDTO>> printForPatient(
            @PathVariable UUID prescriptionId) {
        RoutingDecisionResponseDTO result = stockOutRoutingService.printForPatient(prescriptionId);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(result));
    }

    @PostMapping("/back-order/{prescriptionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Place prescription on back order",
            description = "Mark prescription as pending stock with optional estimated restock date")
    @ApiResponse(responseCode = "201", description = "Prescription placed on back order")
    public ResponseEntity<ApiResponseWrapper<RoutingDecisionResponseDTO>> backOrder(
            @PathVariable UUID prescriptionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate estimatedRestockDate) {
        RoutingDecisionResponseDTO result = stockOutRoutingService.backOrder(prescriptionId, estimatedRestockDate);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(result));
    }

    @PostMapping("/partner-respond/{routingDecisionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Partner pharmacy response",
            description = "Partner pharmacy accepts or rejects a routed prescription")
    @ApiResponse(responseCode = "200", description = "Response recorded")
    public ResponseEntity<ApiResponseWrapper<RoutingDecisionResponseDTO>> partnerRespond(
            @PathVariable UUID routingDecisionId,
            @RequestParam boolean accepted) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockOutRoutingService.partnerRespond(routingDecisionId, accepted)));
    }

    @PostMapping("/partner-dispense-confirm/{routingDecisionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Confirm partner dispense",
            description = "Confirm that a partner pharmacy has dispensed the medication")
    @ApiResponse(responseCode = "200", description = "Dispense confirmed")
    public ResponseEntity<ApiResponseWrapper<RoutingDecisionResponseDTO>> confirmPartnerDispense(
            @PathVariable UUID routingDecisionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockOutRoutingService.confirmPartnerDispense(routingDecisionId)));
    }

    /**
     * The in-house exit from a partner acceptance that never turned into a
     * delivery. Pharmacist roles only, and narrower than the endpoints around
     * it on purpose: this cancels a partner's claim on a prescription, which
     * is a dispensing judgment rather than an administrative one. No
     * {@code SecurityConfig} matcher covers {@code /pharmacy/**}, so the path
     * rides {@code anyRequest().authenticated()} and this annotation is the
     * gate (StockOutRoutingControllerTest pins the absence of a matcher).
     */
    @PostMapping("/partner-no-show/{routingDecisionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER')")
    @Operation(summary = "Record that a partner never delivered",
            description = "Cancels an ACCEPTED partner routing decision with a reason and returns the "
                    + "prescription to SIGNED, where the hospital pharmacy can fill or re-route it.")
    @ApiResponse(responseCode = "200", description = "No-show recorded")
    public ResponseEntity<ApiResponseWrapper<RoutingDecisionResponseDTO>> partnerNoShow(
            @PathVariable UUID routingDecisionId,
            @Valid @RequestBody PartnerNoShowRequestDTO request) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockOutRoutingService.partnerNoShow(routingDecisionId, request.getReason())));
    }

    @GetMapping("/decisions/prescription/{prescriptionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'DOCTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List routing decisions by prescription",
            description = "Paginated list of routing decisions for a prescription")
    @ApiResponse(responseCode = "200", description = "Routing decisions retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<RoutingDecisionResponseDTO>>> listByPrescription(
            @PathVariable UUID prescriptionId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockOutRoutingService.listByPrescription(prescriptionId, pageable)));
    }

    @GetMapping("/decisions/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'DOCTOR', 'NURSE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List routing decisions by patient",
            description = "Paginated list of routing decisions for a patient")
    @ApiResponse(responseCode = "200", description = "Routing decisions retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<RoutingDecisionResponseDTO>>> listByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                stockOutRoutingService.listByPatient(patientId, pageable)));
    }
}
