package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
    @PreAuthorize("hasAnyRole('DOCTOR', 'PHYSICIAN', 'NURSE_PRACTITIONER')")
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
    @PreAuthorize("hasAnyRole('DOCTOR', 'PHYSICIAN', 'NURSE_PRACTITIONER')")
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
}
