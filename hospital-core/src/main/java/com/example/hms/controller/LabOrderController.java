package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.service.LabOrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotBlank;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lab-orders")
@Tag(name = "Lab Order Management", description = "Endpoints for managing lab orders")
@RequiredArgsConstructor
public class LabOrderController {

    private final LabOrderService labOrderService;
    private final MessageSource messageSource;

    /**
     * Create a new lab order.
     * Doctors, Nurses, Staff, and Admins can create.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Create Lab Order", description = "Creates a new lab order capturing ICD-10 diagnosis, order channel, documentation, and signature metadata")
    @ApiResponse(responseCode = "201", description = "Lab order created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<ApiResponseWrapper<LabOrderResponseDTO>> createLabOrder(
        @Valid @RequestBody LabOrderRequestDTO requestDTO,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabOrderResponseDTO created = labOrderService.createLabOrder(requestDTO, locale);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    /**
     * Get a lab order by ID.
     * Doctors, Nurses, Lab staff, Staff, and Admins can view.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'LAB_MANAGER', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get Lab Order by ID", description = "Fetches a lab order by its ID")
    @ApiResponse(responseCode = "200", description = "Lab order retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Lab order not found")
    public ResponseEntity<ApiResponseWrapper<LabOrderResponseDTO>> getLabOrderById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabOrderResponseDTO response = labOrderService.getLabOrderById(id, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(response));
    }

    /**
     * List/search lab orders.
     * Doctors, Nurses, Lab staff, Staff, and Admins can view.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'LAB_MANAGER', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List Lab Orders", description = "Retrieves paginated list of lab orders")
    @ApiResponse(responseCode = "200", description = "Lab orders retrieved successfully")
    public ResponseEntity<ApiResponseWrapper<Page<LabOrderResponseDTO>>> getAllLabOrders(
        @PageableDefault(size = 20) Pageable pageable,
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        Page<LabOrderResponseDTO> page = labOrderService.searchLabOrders(patientId, fromDate, toDate, pageable, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(page));
    }

    /**
     * Update a lab order.
     * Doctors, Nurses, Staff, and Admins can update.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Update Lab Order", description = "Updates a lab order by ID, including diagnosis, order-channel, and standing-order attestations")
    @ApiResponse(responseCode = "200", description = "Lab order updated successfully")
    @ApiResponse(responseCode = "404", description = "Lab order not found")
    public ResponseEntity<ApiResponseWrapper<LabOrderResponseDTO>> updateLabOrder(
        @PathVariable UUID id,
        @Valid @RequestBody LabOrderRequestDTO requestDTO,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabOrderResponseDTO updated = labOrderService.updateLabOrder(id, requestDTO, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(updated));
    }

    /**
     * Delete a lab order.
     * Only Admins can delete.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Delete Lab Order", description = "Deletes a lab order")
    @ApiResponse(responseCode = "200", description = "Lab order deleted successfully")
    @ApiResponse(responseCode = "404", description = "Lab order not found")
    public ResponseEntity<ApiResponseWrapper<String>> deleteLabOrder(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        labOrderService.deleteLabOrder(id, locale);
        String message = messageSource.getMessage("laborder.deleted", new Object[]{id}, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(message));
    }

    /**
     * Transition a lab order to a new status.
     * Allowed roles vary by target status (see LabOrderServiceImpl for rules).
     */
    @PostMapping("/{id}/transition")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Transition Lab Order Status",
               description = "Advances a lab order through its lifecycle: ORDERED → PENDING → COLLECTED → RECEIVED → IN_PROGRESS → RESULTED → VERIFIED → COMPLETED. Role-based guards are enforced per transition.")
    @ApiResponse(responseCode = "200", description = "Lab order transitioned successfully")
    @ApiResponse(responseCode = "400", description = "Invalid transition")
    @ApiResponse(responseCode = "404", description = "Lab order not found")
    public ResponseEntity<ApiResponseWrapper<LabOrderResponseDTO>> transitionLabOrderStatus(
        @PathVariable UUID id,
        @Valid @RequestBody TransitionLabOrderStatusRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        String rawStatus = request == null ? null : request.getStatus();
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new com.example.hms.exception.BusinessException("Request body must include a 'status' field.");
        }
        LabOrderStatus toStatus;
        try {
            toStatus = LabOrderStatus.valueOf(rawStatus.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new com.example.hms.exception.BusinessException("Unknown lab order status: " + rawStatus);
        }
        LabOrderResponseDTO result = labOrderService.transitionLabOrderStatus(id, toStatus, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }

    /** Request DTO for transitioning a lab order's status. */
    private static class TransitionLabOrderStatusRequestDTO {
        @NotBlank(message = "Request body must include a 'status' field.")
        private String status;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
