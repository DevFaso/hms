package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentResponseDTO;
import com.example.hms.service.pharmacy.PharmacyPaymentService;
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

/**
 * T-41: pharmacy payment checkout endpoints. Supports recording cash, mobile-money,
 * and insurance payments against a {@code Dispense}. Read endpoints in this controller
 * are staff-facing payment history and invoice views; the patient portal invoice view
 * (T-45) is served from {@code /me/patient/pharmacy/payments} in
 * {@link com.example.hms.controller.PatientPortalController} which resolves the patient
 * from the JWT (prevents IDOR).
 */
@RestController
@RequestMapping("/pharmacy/payments")
@Tag(name = "Pharmacy Payments", description = "Pharmacy checkout & payment history")
@RequiredArgsConstructor
public class PharmacyPaymentController {

    private final PharmacyPaymentService pharmacyPaymentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CASHIER', 'BILLING_SPECIALIST', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Record pharmacy payment",
            description = "Create a payment (cash, mobile-money, or insurance) against a dispense")
    @ApiResponse(responseCode = "201", description = "Payment recorded")
    @ApiResponse(responseCode = "400", description = "Invalid request or provider error")
    @ApiResponse(responseCode = "404", description = "Dispense, patient, or hospital not found")
    public ResponseEntity<ApiResponseWrapper<PharmacyPaymentResponseDTO>> create(
            @Valid @RequestBody PharmacyPaymentRequestDTO dto) {
        PharmacyPaymentResponseDTO created = pharmacyPaymentService.createPayment(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CASHIER', 'BILLING_SPECIALIST', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get pharmacy payment", description = "Retrieve a pharmacy payment by ID")
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    public ResponseEntity<ApiResponseWrapper<PharmacyPaymentResponseDTO>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(pharmacyPaymentService.getPayment(id)));
    }

    @GetMapping("/dispense/{dispenseId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CASHIER', 'BILLING_SPECIALIST', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List payments by dispense",
            description = "Paginated list of payments for a dispense record")
    @ApiResponse(responseCode = "200", description = "Payments retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyPaymentResponseDTO>>> listByDispense(
            @PathVariable UUID dispenseId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                pharmacyPaymentService.listByDispense(dispenseId, pageable)));
    }

    // PATIENT role intentionally excluded: PATIENT callers use the patient-portal `/me` flow
    // which resolves the patient ID from the JWT, preventing IDOR via arbitrary {patientId}.
    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CASHIER', 'BILLING_SPECIALIST', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List payments by patient",
            description = "Paginated list of payments for a patient \u2014 staff-facing invoice view")
    @ApiResponse(responseCode = "200", description = "Payments retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyPaymentResponseDTO>>> listByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                pharmacyPaymentService.listByPatient(patientId, pageable)));
    }
}
