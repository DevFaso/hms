package com.example.hms.controller;

import com.example.hms.payload.dto.billing.PayerDTO;
import com.example.hms.payload.dto.billing.PayerPlanRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanRuleRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanUpdateRequestDTO;
import com.example.hms.payload.dto.billing.PayerReasonRequestDTO;
import com.example.hms.payload.dto.billing.PayerRequestDTO;
import com.example.hms.payload.dto.billing.PayerUpdateRequestDTO;
import com.example.hms.service.billing.PayerCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (scorecard M1): the hospital's payer catalogue ("organismes payeurs") at
 * {@code /billing/payers} - payers, their plans ("formules") and the plans' rules.
 *
 * <p>Reads: hospital admin, accountant, billing specialist, receptionist (to pick a patient's plan), claims reviewer,
 * and the platform super-admin (read-only). Writes: hospital admin and accountant only (owner decision D-1); the
 * service then requires that role on an assignment AT the acting hospital, so the super-admin's inherited authority
 * alone never writes.
 *
 * <p>There is deliberately no endpoint that edits or deletes a rule: a rule changes only by adding one, or ends with a
 * reason. Nothing is deleted.
 */
@RestController
@RequestMapping("/billing/payers")
@Tag(name = "Payer catalogue", description = "The hospital's payers, their plans and the plans' coverage rules")
@RequiredArgsConstructor
public class PayerController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST','ROLE_CLAIMS_REVIEWER')";
    static final String MAINTAINERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";

    private final PayerCatalogueService payerCatalogueService;

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "List the payers with their plans and rules")
    public List<PayerDTO> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return payerCatalogueService.list(includeInactive);
    }

    @GetMapping("/{payerId}")
    @PreAuthorize(READERS)
    @Operation(summary = "One payer with its plans and their rules, history included")
    public PayerDTO get(@PathVariable UUID payerId) {
        return payerCatalogueService.get(payerId);
    }

    @PostMapping
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Create a payer")
    public ResponseEntity<PayerDTO> create(@Valid @RequestBody(required = false) PayerRequestDTO request) {
        return new ResponseEntity<>(payerCatalogueService.createPayer(request), HttpStatus.CREATED);
    }

    @PutMapping("/{payerId}")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Edit a payer's name and type (never its code)")
    public PayerDTO update(@PathVariable UUID payerId,
                           @Valid @RequestBody(required = false) PayerUpdateRequestDTO request) {
        return payerCatalogueService.updatePayer(payerId, request);
    }

    @PostMapping("/{payerId}/deactivate")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Deactivate a payer with a reason; its plans are no longer linked to anyone new")
    public PayerDTO deactivate(@PathVariable UUID payerId,
                               @Valid @RequestBody(required = false) PayerReasonRequestDTO request) {
        return payerCatalogueService.deactivatePayer(payerId, request);
    }

    @PostMapping("/{payerId}/plans")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Add a plan (formule) to a payer")
    public ResponseEntity<PayerDTO> createPlan(@PathVariable UUID payerId,
                                               @Valid @RequestBody(required = false) PayerPlanRequestDTO request) {
        return new ResponseEntity<>(payerCatalogueService.createPlan(payerId, request), HttpStatus.CREATED);
    }

    @PutMapping("/{payerId}/plans/{planId}")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Edit a plan's name, rate, ceilings and dates (never its code)")
    public PayerDTO updatePlan(@PathVariable UUID payerId, @PathVariable UUID planId,
                               @Valid @RequestBody(required = false) PayerPlanUpdateRequestDTO request) {
        return payerCatalogueService.updatePlan(payerId, planId, request);
    }

    @PostMapping("/{payerId}/plans/{planId}/rules")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "Add a rule (rate, exclusion or ceiling per act) on a category or a price-list item")
    public ResponseEntity<PayerDTO> addRule(@PathVariable UUID payerId, @PathVariable UUID planId,
                                            @Valid @RequestBody(required = false) PayerPlanRuleRequestDTO request) {
        return new ResponseEntity<>(payerCatalogueService.addRule(payerId, planId, request), HttpStatus.CREATED);
    }

    @PostMapping("/{payerId}/plans/{planId}/rules/{ruleId}/end")
    @PreAuthorize(MAINTAINERS)
    @Operation(summary = "End a rule from today with a reason (a rule not started yet is withdrawn)")
    public PayerDTO endRule(@PathVariable UUID payerId, @PathVariable UUID planId, @PathVariable UUID ruleId,
                            @Valid @RequestBody(required = false) PayerReasonRequestDTO request) {
        return payerCatalogueService.endRule(payerId, planId, ruleId, request);
    }
}
