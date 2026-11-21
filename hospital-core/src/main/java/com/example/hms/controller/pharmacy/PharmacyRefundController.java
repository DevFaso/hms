package com.example.hms.controller.pharmacy;

import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundPayRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundReasonRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.pharmacy.PharmacyRefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * QA D2676 (r65, billing-money plan PR 13): money given back for a patient return, under the two-person refund rule.
 * The roles below are the outer gate; the service checks each step's role on the assignment at the acting hospital
 * (never the super-admin alone) and refuses self-approval.
 */
@RestController
@RequestMapping("/pharmacy/refunds")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Refunds", description = "Refunds of patient returns (two-person rule)")
@SecurityRequirement(name = "bearerAuth")
public class PharmacyRefundController {

    private static final String READERS =
        "hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN', 'ACCOUNTANT', 'BILLING_SPECIALIST')";
    /** QA D2120: a write a pharmacist makes admits no super-admin on their own. */
    private static final String REQUESTERS = "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'PHARMACIST','HOSPITAL_ADMIN','ACCOUNTANT','BILLING_SPECIALIST')";
    private static final String APPROVERS = "hasAnyRole('HOSPITAL_ADMIN', 'ACCOUNTANT')";
    private static final String PAYERS = "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'PHARMACIST','HOSPITAL_ADMIN','BILLING_SPECIALIST')";

    private final PharmacyRefundService refundService;

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "Pharmacy refunds of the hospital by status (REQUESTED and APPROVED by default)")
    public ResponseEntity<ApiResponseWrapper<List<PharmacyRefundDTO>>> list(
            @RequestParam(name = "status", required = false) List<PharmacyRefundStatus> statuses) {
        return ResponseEntity.ok(ApiResponseWrapper.success(refundService.list(statuses)));
    }

    @WriteAudited(skip = true, reason = "service emits PHARMACY_REFUND_REQUESTED")
    @PostMapping
    @PreAuthorize(REQUESTERS)
    @Operation(summary = "Ask for the refund of a patient return (amount computed by the server)")
    public ResponseEntity<ApiResponseWrapper<PharmacyRefundDTO>> request(
            @Valid @RequestBody PharmacyRefundRequestDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(refundService.request(body)));
    }

    @WriteAudited(skip = true, reason = "service emits PHARMACY_REFUND_APPROVED")
    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Approve a requested refund (another hospital admin or accountant)")
    public ResponseEntity<ApiResponseWrapper<PharmacyRefundDTO>> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(refundService.approve(id)));
    }

    @WriteAudited(skip = true, reason = "service emits PHARMACY_REFUND_REJECTED")
    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Reject a requested refund, with the reason")
    public ResponseEntity<ApiResponseWrapper<PharmacyRefundDTO>> reject(
            @PathVariable UUID id, @Valid @RequestBody PharmacyRefundReasonRequestDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(refundService.reject(id, body)));
    }

    @WriteAudited(skip = true, reason = "service emits PHARMACY_REFUND_PAID")
    @PostMapping("/{id}/pay")
    @PreAuthorize(PAYERS)
    @Operation(summary = "Hand an approved refund to the patient (money out)")
    public ResponseEntity<ApiResponseWrapper<PharmacyRefundDTO>> pay(
            @PathVariable UUID id, @Valid @RequestBody(required = false) PharmacyRefundPayRequestDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(refundService.pay(id, body)));
    }

    @WriteAudited(skip = true, reason = "service emits PHARMACY_REFUND_CANCELLED")
    @PostMapping("/{id}/cancel")
    @PreAuthorize(REQUESTERS)
    @Operation(summary = "Withdraw a refund not yet paid, with the reason")
    public ResponseEntity<ApiResponseWrapper<PharmacyRefundDTO>> cancel(
            @PathVariable UUID id, @Valid @RequestBody PharmacyRefundReasonRequestDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(refundService.cancel(id, body)));
    }
}
