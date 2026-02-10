package com.example.hms.controller;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.payload.dto.procedure.ProcedureOrderRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderUpdateDTO;
import com.example.hms.service.ProcedureOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/procedure-orders")
@Validated
@RequiredArgsConstructor
@Tag(name = "Procedure Orders", description = "Pre-procedure ordering, scheduling, consent tracking, and sedation requirements")
public class ProcedureOrderController {

    private final ProcedureOrderService procedureOrderService;

    @PostMapping
    @PreAuthorize("hasAuthority('ORDER_PROCEDURES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE')")
    @Operation(summary = "Create procedure order",
               description = "Order pre-procedure interventions (surgical, diagnostic, therapeutic procedures)")
    public ResponseEntity<ProcedureOrderResponseDTO> createProcedureOrder(
        @Valid @RequestBody ProcedureOrderRequestDTO request,
        Authentication authentication
    ) {
        UUID orderingProviderId = extractUserId(authentication);
        ProcedureOrderResponseDTO response = procedureOrderService.createProcedureOrder(request, orderingProviderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAuthority('VIEW_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','PATIENT')")
    @Operation(summary = "Get procedure order details")
    public ResponseEntity<ProcedureOrderResponseDTO> getProcedureOrder(@PathVariable UUID orderId) {
        ProcedureOrderResponseDTO response = procedureOrderService.getProcedureOrder(orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAuthority('VIEW_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','PATIENT')")
    @Operation(summary = "List procedure orders for patient")
    public ResponseEntity<List<ProcedureOrderResponseDTO>> getProcedureOrdersForPatient(@PathVariable UUID patientId) {
        List<ProcedureOrderResponseDTO> orders = procedureOrderService.getProcedureOrdersForPatient(patientId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAuthority('VIEW_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE')")
    @Operation(summary = "List procedure orders for hospital",
               description = "Filter by status: ORDERED, SCHEDULED, PRE_OP_CLEARANCE_PENDING, READY_FOR_PROCEDURE, IN_PROGRESS, COMPLETED, CANCELLED")
    public ResponseEntity<List<ProcedureOrderResponseDTO>> getProcedureOrdersForHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) ProcedureOrderStatus status
    ) {
        List<ProcedureOrderResponseDTO> orders = procedureOrderService.getProcedureOrdersForHospital(hospitalId, status);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/hospital/{hospitalId}/scheduled")
    @PreAuthorize("hasAuthority('VIEW_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE')")
    @Operation(summary = "Get procedures scheduled within date range",
               description = "Retrieve procedure schedule for OR planning and resource allocation")
    public ResponseEntity<List<ProcedureOrderResponseDTO>> getScheduledProcedures(
        @PathVariable UUID hospitalId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<ProcedureOrderResponseDTO> orders = procedureOrderService.getProcedureOrdersScheduledBetween(hospitalId, startDate, endDate);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/hospital/{hospitalId}/pending-consent")
    @PreAuthorize("hasAuthority('VIEW_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE')")
    @Operation(summary = "Get procedures awaiting consent",
               description = "List scheduled procedures without completed consent forms")
    public ResponseEntity<List<ProcedureOrderResponseDTO>> getPendingConsentOrders(@PathVariable UUID hospitalId) {
        List<ProcedureOrderResponseDTO> orders = procedureOrderService.getPendingConsentOrders(hospitalId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/ordered-by/{providerId}")
    @PreAuthorize("hasAuthority('VIEW_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE')")
    @Operation(summary = "List procedure orders ordered by provider")
    public ResponseEntity<List<ProcedureOrderResponseDTO>> getProcedureOrdersOrderedBy(@PathVariable UUID providerId) {
        List<ProcedureOrderResponseDTO> orders = procedureOrderService.getProcedureOrdersOrderedBy(providerId);
        return ResponseEntity.ok(orders);
    }

    @PutMapping("/{orderId}")
    @PreAuthorize("hasAuthority('UPDATE_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE')")
    @Operation(summary = "Update procedure order",
               description = "Update scheduling, consent status, site marking, or order status")
    public ResponseEntity<ProcedureOrderResponseDTO> updateProcedureOrder(
        @PathVariable UUID orderId,
        @Valid @RequestBody ProcedureOrderUpdateDTO updateDTO
    ) {
        ProcedureOrderResponseDTO response = procedureOrderService.updateProcedureOrder(orderId, updateDTO);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAuthority('CANCEL_PROCEDURE_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Cancel procedure order",
               description = "Cancel procedure with reason (patient declined, condition improved, etc.)")
    public ResponseEntity<ProcedureOrderResponseDTO> cancelProcedureOrder(
        @PathVariable UUID orderId,
        @RequestParam String cancellationReason
    ) {
        ProcedureOrderResponseDTO response = procedureOrderService.cancelProcedureOrder(orderId, cancellationReason);
        return ResponseEntity.ok(response);
    }

    // Helper method to extract user ID from authentication
    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new IllegalArgumentException("Authentication required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
            try {
                return UUID.fromString(userDetails.getUsername());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid user ID format in authentication");
            }
        }
        if (principal instanceof String principalString) {
            try {
                return UUID.fromString(principalString);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid user ID format in authentication");
            }
        }
        throw new IllegalArgumentException("Unsupported authentication principal type");
    }
}
