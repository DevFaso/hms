package com.example.hms.controller.pharmacy;

import com.example.hms.enums.QueueClaimFilter;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.payload.dto.pharmacy.DispenseSettingsDTO;
import com.example.hms.payload.dto.pharmacy.HandOverRequestDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueueClaimDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO;
import com.example.hms.service.pharmacy.DispenseService;
import com.example.hms.service.pharmacy.PrescriptionQueueClaimService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/pharmacy/dispense")
@Tag(name = "Pharmacy Dispensing", description = "Prescription dispensing workflow")
@RequiredArgsConstructor
public class DispenseController {

    private final DispenseService dispenseService;
    private final PrescriptionQueueClaimService queueClaimService;

    @GetMapping("/work-queue")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Pharmacist work queue",
            description = "Paginated list of prescriptions ready for dispensing at the current hospital. "
                    + "claim=MINE lists the caller's active claims, claim=UNCLAIMED the rows nobody holds "
                    + "and no fill is prepared for (G13); ignored when claims are off.")
    @ApiResponse(responseCode = "200", description = "Work queue retrieved")
    @ApiResponse(responseCode = "400", description = "Unknown claim filter")
    public ResponseEntity<ApiResponseWrapper<Page<WorkQueuePrescriptionDTO>>> getWorkQueue(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            @RequestParam(name = "claim", defaultValue = "ALL") QueueClaimFilter claim) {
        return ResponseEntity.ok(ApiResponseWrapper.success(dispenseService.getWorkQueue(pageable, claim)));
    }

    @PostMapping("/work-queue/{prescriptionId}/claim")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Claim a work-queue prescription",
            description = "Say that the caller is preparing this order (G13). Advisory: nothing else is "
                    + "refused because of it. Claiming one's own active claim renews it.")
    @ApiResponse(responseCode = "200", description = "Claimed, or renewed (renewed=true)")
    @ApiResponse(responseCode = "404", description = "Prescription outside scope, or claims are off")
    @ApiResponse(responseCode = "409", description = "Held by another pharmacist, not on the queue, a fill is prepared, or a concurrent change")
    public ResponseEntity<ApiResponseWrapper<WorkQueueClaimDTO>> claimQueueRow(@PathVariable UUID prescriptionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(queueClaimService.claim(prescriptionId)));
    }

    @PostMapping("/work-queue/{prescriptionId}/claim/take-over")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Take a work-queue claim over",
            description = "Take a colleague's claim over, audited with the previous holder (G13)")
    @ApiResponse(responseCode = "200", description = "Taken over (or claimed, when nobody held it)")
    @ApiResponse(responseCode = "404", description = "Prescription outside scope, or claims are off")
    @ApiResponse(responseCode = "409", description = "Not on the queue, a fill is prepared, or a concurrent change")
    public ResponseEntity<ApiResponseWrapper<WorkQueueClaimDTO>> takeOverQueueRow(@PathVariable UUID prescriptionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(queueClaimService.takeOver(prescriptionId)));
    }

    @PostMapping("/work-queue/{prescriptionId}/claim/release")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Release a work-queue claim",
            description = "Release one's own claim (G13). Nothing to release answers the same.")
    @ApiResponse(responseCode = "200", description = "Released, or nothing to release")
    @ApiResponse(responseCode = "404", description = "Prescription outside scope, or claims are off")
    @ApiResponse(responseCode = "409", description = "Another pharmacist holds the claim, or a concurrent change")
    public ResponseEntity<ApiResponseWrapper<Void>> releaseQueueRow(@PathVariable UUID prescriptionId) {
        queueClaimService.release(prescriptionId);
        return ResponseEntity.ok(ApiResponseWrapper.success(null));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Dispense medication",
            description = "Create a dispense record, decrement stock, and update prescription status")
    @ApiResponse(responseCode = "201", description = "Medication dispensed")
    @ApiResponse(responseCode = "400", description = "Invalid request or insufficient stock")
    @ApiResponse(responseCode = "404", description = "Prescription, patient, or pharmacy not found")
    public ResponseEntity<ApiResponseWrapper<DispenseResponseDTO>> dispense(
            @Valid @RequestBody DispenseRequestDTO dto) {
        DispenseResponseDTO created = dispenseService.createDispense(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Dispensing settings",
            description = "Server-side switches the dispensing screen needs: whether a fill can be marked ready "
                    + "for collection, and whether work-queue claims are on and how long one lasts")
    @ApiResponse(responseCode = "200", description = "Settings returned")
    public ResponseEntity<ApiResponseWrapper<DispenseSettingsDTO>> getSettings() {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                DispenseSettingsDTO.builder()
                        .readyForCollectionEnabled(dispenseService.isReadyForCollectionEnabled())
                        .queueClaimEnabled(queueClaimService.isEnabled())
                        .queueClaimTtlMinutes(queueClaimService.ttl().toMinutes())
                        .build()));
    }

    @PostMapping("/ready")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Mark ready for collection",
            description = "Prepare a fill (stock set aside, status PENDING) and text the patient that it is ready")
    @ApiResponse(responseCode = "201", description = "Fill prepared")
    @ApiResponse(responseCode = "400", description = "Not dispensable, CDS, verification, or a status in the body")
    @ApiResponse(responseCode = "404", description = "Prescription or pharmacy outside scope, or the feature is off")
    @ApiResponse(responseCode = "409", description = "A preparation is already open, or a concurrent change")
    public ResponseEntity<ApiResponseWrapper<DispenseResponseDTO>> markReady(
            @Valid @RequestBody DispenseRequestDTO dto) {
        DispenseResponseDTO prepared = dispenseService.markReadyForCollection(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(prepared));
    }

    @PostMapping("/{id}/hand-over")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Hand over a prepared fill",
            description = "Complete a fill prepared for collection: the patient has it")
    @ApiResponse(responseCode = "200", description = "Handed over (a repeat answers the same)")
    @ApiResponse(responseCode = "400", description = "Expired lot, wrong patient, or controlled substance")
    @ApiResponse(responseCode = "404", description = "Dispense record not found")
    @ApiResponse(responseCode = "409", description = "Not waiting for collection, order no longer dispensable, or a concurrent change")
    public ResponseEntity<ApiResponseWrapper<DispenseResponseDTO>> handOver(
            @PathVariable UUID id, @Valid @RequestBody(required = false) HandOverRequestDTO request) {
        return ResponseEntity.ok(ApiResponseWrapper.success(dispenseService.handOver(id, request)));
    }

    @PostMapping("/{id}/cancel-ready")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Cancel a preparation",
            description = "Cancel a fill prepared for collection; the stock is returned and the patient told")
    @ApiResponse(responseCode = "200", description = "Preparation cancelled")
    @ApiResponse(responseCode = "400", description = "Reason missing or not a pharmacist's choice")
    @ApiResponse(responseCode = "404", description = "Dispense record not found")
    @ApiResponse(responseCode = "409", description = "Not waiting for collection, or a concurrent change")
    public ResponseEntity<ApiResponseWrapper<DispenseResponseDTO>> cancelReady(
            @PathVariable UUID id, @Valid @RequestBody CancelReadyRequestDTO request) {
        return ResponseEntity.ok(ApiResponseWrapper.success(dispenseService.cancelReady(id, request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get dispense record", description = "Retrieve a dispense record by ID")
    @ApiResponse(responseCode = "200", description = "Dispense record found")
    @ApiResponse(responseCode = "404", description = "Dispense record not found")
    public ResponseEntity<ApiResponseWrapper<DispenseResponseDTO>> getDispense(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(dispenseService.getDispense(id)));
    }

    @GetMapping("/prescription/{prescriptionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'DOCTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List dispenses by prescription",
            description = "Paginated list of dispenses for a prescription")
    @ApiResponse(responseCode = "200", description = "Dispenses retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<DispenseResponseDTO>>> listByPrescription(
            @PathVariable UUID prescriptionId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                dispenseService.listByPrescription(prescriptionId, pageable)));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'DOCTOR', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List dispenses by patient",
            description = "Paginated list of dispenses for a patient")
    @ApiResponse(responseCode = "200", description = "Dispenses retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<DispenseResponseDTO>>> listByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                dispenseService.listByPatient(patientId, pageable)));
    }

    @GetMapping("/pharmacy/{pharmacyId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'STORE_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List dispenses by pharmacy",
            description = "Paginated list of dispenses at a pharmacy")
    @ApiResponse(responseCode = "200", description = "Dispenses retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<DispenseResponseDTO>>> listByPharmacy(
            @PathVariable UUID pharmacyId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                dispenseService.listByPharmacy(pharmacyId, pageable)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Cancel dispense",
            description = "Cancel a dispense and reverse stock changes")
    @ApiResponse(responseCode = "200", description = "Dispense cancelled and stock reversed")
    @ApiResponse(responseCode = "400", description = "Cannot cancel this dispense")
    @ApiResponse(responseCode = "404", description = "Dispense record not found")
    public ResponseEntity<ApiResponseWrapper<DispenseResponseDTO>> cancelDispense(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(dispenseService.cancelDispense(id)));
    }
}
