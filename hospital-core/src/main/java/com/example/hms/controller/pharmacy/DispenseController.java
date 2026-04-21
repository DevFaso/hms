package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO;
import com.example.hms.service.pharmacy.DispenseService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/pharmacy/dispense")
@Tag(name = "Pharmacy Dispensing", description = "Prescription dispensing workflow")
@RequiredArgsConstructor
public class DispenseController {

    private final DispenseService dispenseService;

    @GetMapping("/work-queue")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Pharmacist work queue",
            description = "Paginated list of prescriptions ready for dispensing at the current hospital")
    @ApiResponse(responseCode = "200", description = "Work queue retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<WorkQueuePrescriptionDTO>>> getWorkQueue(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(dispenseService.getWorkQueue(pageable)));
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
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_VERIFIER', 'DOCTOR', 'NURSE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
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
