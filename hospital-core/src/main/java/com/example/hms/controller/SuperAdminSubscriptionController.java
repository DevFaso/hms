package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionRequestDTO;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionResponseDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanRequestDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanResponseDTO;
import com.example.hms.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * MVP-6: Super-admin CRUD for subscription plans + per-org assignment.
 */
@RestController
@RequestMapping("/super-admin/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Super Admin Subscriptions",
    description = "Plan catalogue + per-organization subscription assignment (MVP-6 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminSubscriptionController {

    private final SubscriptionService service;

    @GetMapping("/plans")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List subscription plans (active by default)")
    public ResponseEntity<List<SubscriptionPlanResponseDTO>> listPlans(
        @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly
    ) {
        return ResponseEntity.ok(service.listPlans(activeOnly));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a subscription plan")
    public ResponseEntity<SubscriptionPlanResponseDTO> createPlan(
        @Valid @RequestBody SubscriptionPlanRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan(request));
    }

    @PutMapping("/plans/{planId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update a subscription plan")
    public ResponseEntity<SubscriptionPlanResponseDTO> updatePlan(
        @PathVariable UUID planId,
        @Valid @RequestBody SubscriptionPlanRequestDTO request
    ) {
        return ResponseEntity.ok(service.updatePlan(planId, request));
    }

    @DeleteMapping("/plans/{planId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Soft-deactivate a plan (keeps history; rejects new assignments)")
    public ResponseEntity<Void> deactivatePlan(@PathVariable UUID planId) {
        service.deactivatePlan(planId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/organizations/{organizationId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List subscriptions for an organization (every status)")
    public ResponseEntity<List<OrganizationSubscriptionResponseDTO>> listForOrganization(
        @PathVariable UUID organizationId
    ) {
        return ResponseEntity.ok(service.listForOrganization(organizationId));
    }

    @GetMapping("/organizations/{organizationId}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get the currently ACTIVE subscription for an organization")
    public ResponseEntity<OrganizationSubscriptionResponseDTO> getActiveForOrganization(
        @PathVariable UUID organizationId
    ) {
        OrganizationSubscriptionResponseDTO active = service.getActiveForOrganization(organizationId);
        return active == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(active);
    }

    @PostMapping("/organizations/{organizationId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Assign or change the active subscription for an organization")
    public ResponseEntity<OrganizationSubscriptionResponseDTO> assignPlan(
        @PathVariable UUID organizationId,
        @Valid @RequestBody OrganizationSubscriptionRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.assignPlan(organizationId, request));
    }

    @DeleteMapping("/organizations/{organizationId}/{subscriptionId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cancel an existing subscription row (must belong to the organization on the URL)")
    public ResponseEntity<OrganizationSubscriptionResponseDTO> cancel(
        @PathVariable UUID organizationId,
        @PathVariable UUID subscriptionId
    ) {
        // PR #228 review — the service now verifies that the subscription
        // actually belongs to the organization on the URL and throws 400
        // otherwise. Closes the cross-tenant cancel hole the controller
        // previously left open.
        return ResponseEntity.ok(service.cancel(organizationId, subscriptionId));
    }
}
