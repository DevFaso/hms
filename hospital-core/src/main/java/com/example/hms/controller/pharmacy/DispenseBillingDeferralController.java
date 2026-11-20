package com.example.hms.controller.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDecisionDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralReasonDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.pharmacy.DispenseBillingDeferralService;
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
 * Policy rule 6 (provisional, V345): « à facturer » as an approved exception. Somebody asks, another person approves
 * or rejects, the pharmacist executes at the hand-over; an urgency override is reviewed afterwards. The roles below
 * are the outer gate; the service checks each step's role on the assignment at the acting hospital (never the
 * super-admin alone), by the kind of deferral, and refuses self-approval and self-review.
 */
@RestController
@RequestMapping("/pharmacy/billing-deferrals")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Billing Deferrals", description = "« À facturer »: request, approval, execution and review")
@SecurityRequirement(name = "bearerAuth")
public class DispenseBillingDeferralController {

    private static final String READERS =
        "hasAnyRole('PHARMACIST', 'BILLING_SPECIALIST', 'ACCOUNTANT', 'HOSPITAL_ADMIN')";
    /** QA D2120: a write a pharmacist makes admits no super-admin on their own. */
    private static final String REQUESTERS = "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'PHARMACIST','BILLING_SPECIALIST','ACCOUNTANT','HOSPITAL_ADMIN')";
    /** Never the pharmacist: billing, finance or a hospital admin. */
    private static final String APPROVERS = "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'BILLING_SPECIALIST','ACCOUNTANT','HOSPITAL_ADMIN')";

    private final DispenseBillingDeferralService deferralService;

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "Deferrals of the hospital: the open ones and the overrides to review by default, or by status")
    public ResponseEntity<ApiResponseWrapper<List<DispenseBillingDeferralDTO>>> list(
            @RequestParam(name = "status", required = false) List<DispenseBillingDeferralStatus> statuses,
            @RequestParam(name = "prescriptionId", required = false) UUID prescriptionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(deferralService.list(statuses, prescriptionId)));
    }

    @GetMapping("/counter/{prescriptionId}")
    @PreAuthorize(READERS)
    @Operation(summary = "What the counter needs before a fill leaves unpaid: an open stay, or the open deferrals")
    public ResponseEntity<ApiResponseWrapper<com.example.hms.payload.dto.pharmacy.DispenseBillingCounterDTO>> counter(
            @PathVariable UUID prescriptionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(deferralService.counter(prescriptionId)));
    }

    @WriteAudited(skip = true, reason = "service emits DISPENSE_BILLING_DEFERRAL_REQUESTED")
    @PostMapping
    @PreAuthorize(REQUESTERS)
    @Operation(summary = "Ask for a fill of a prescription to leave « à facturer » (third-party cover or credit)")
    public ResponseEntity<ApiResponseWrapper<DispenseBillingDeferralDTO>> request(
            @Valid @RequestBody DispenseBillingDeferralRequestDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponseWrapper.success(deferralService.request(body)));
    }

    @WriteAudited(skip = true, reason = "service emits DISPENSE_BILLING_DEFERRAL_APPROVED")
    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Approve a requested deferral (another person: billing, finance or a hospital admin)")
    public ResponseEntity<ApiResponseWrapper<DispenseBillingDeferralDTO>> approve(
            @PathVariable UUID id, @Valid @RequestBody(required = false) DispenseBillingDeferralDecisionDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(deferralService.approve(id, body)));
    }

    @WriteAudited(skip = true, reason = "service emits DISPENSE_BILLING_DEFERRAL_REJECTED")
    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Reject a requested deferral, with the reason")
    public ResponseEntity<ApiResponseWrapper<DispenseBillingDeferralDTO>> reject(
            @PathVariable UUID id, @Valid @RequestBody DispenseBillingDeferralReasonDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(deferralService.reject(id, body)));
    }

    @WriteAudited(skip = true, reason = "service emits DISPENSE_BILLING_DEFERRAL_CANCELLED")
    @PostMapping("/{id}/cancel")
    @PreAuthorize(REQUESTERS)
    @Operation(summary = "Withdraw a deferral no fill left on yet, with the reason")
    public ResponseEntity<ApiResponseWrapper<DispenseBillingDeferralDTO>> cancel(
            @PathVariable UUID id, @Valid @RequestBody DispenseBillingDeferralReasonDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(deferralService.cancel(id, body)));
    }

    @WriteAudited(skip = true, reason = "service emits DISPENSE_BILLING_OVERRIDE_REVIEWED")
    @PostMapping("/{id}/review")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Review an urgency override afterwards, with a note (never the pharmacist who made it)")
    public ResponseEntity<ApiResponseWrapper<DispenseBillingDeferralDTO>> review(
            @PathVariable UUID id, @Valid @RequestBody DispenseBillingDeferralReasonDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(deferralService.review(id, body)));
    }
}
