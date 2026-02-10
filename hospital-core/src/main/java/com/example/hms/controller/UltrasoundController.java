package com.example.hms.controller;

import com.example.hms.enums.UltrasoundOrderStatus;

import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.service.UltrasoundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ultrasound")
@RequiredArgsConstructor
@Validated
@Tag(name = "Ultrasound Scans", description = "Prenatal ultrasound order and reporting workflows for midwives")
public class UltrasoundController {

    private final UltrasoundService ultrasoundService;

    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Create a new ultrasound order for a patient")
    public ResponseEntity<UltrasoundOrderResponseDTO> createOrder(
        @Valid @RequestBody UltrasoundOrderRequestDTO request,
        Authentication authentication
    ) {
        // Extract userId from authentication - assuming username is the userId or has userId in principal
        UUID userId = extractUserId(authentication);
        UltrasoundOrderResponseDTO response = ultrasoundService.createOrder(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Update an existing ultrasound order")
    public ResponseEntity<UltrasoundOrderResponseDTO> updateOrder(
        @PathVariable UUID orderId,
        @Valid @RequestBody UltrasoundOrderRequestDTO request,
        Authentication authentication
    ) {
        UltrasoundOrderResponseDTO response = ultrasoundService.updateOrder(orderId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Cancel an ultrasound order")
    public ResponseEntity<UltrasoundOrderResponseDTO> cancelOrder(
        @PathVariable UUID orderId,
        @RequestParam(required = false) String cancellationReason,
        Authentication authentication
    ) {
        UltrasoundOrderResponseDTO response = ultrasoundService.cancelOrder(orderId, cancellationReason);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE','NURSE','PATIENT')")
    @Operation(summary = "Retrieve a single ultrasound order by ID")
    public ResponseEntity<UltrasoundOrderResponseDTO> getOrderById(
        @PathVariable UUID orderId,
        Authentication authentication
    ) {
        UltrasoundOrderResponseDTO response = ultrasoundService.getOrderById(orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orders/patient/{patientId}")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE','NURSE','PATIENT')")
    @Operation(summary = "List all ultrasound orders for a patient")
    public ResponseEntity<List<UltrasoundOrderResponseDTO>> getOrdersByPatientId(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UltrasoundOrderStatus status,
        Authentication authentication
    ) {
        List<UltrasoundOrderResponseDTO> orders;
        if (status != null) {
            orders = ultrasoundService.getOrdersByPatientIdAndStatus(patientId, status);
        } else {
            orders = ultrasoundService.getOrdersByPatientId(patientId);
        }
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/orders/hospital/{hospitalId}")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "List all ultrasound orders for a hospital")
    public ResponseEntity<List<UltrasoundOrderResponseDTO>> getOrdersByHospitalId(
        @PathVariable UUID hospitalId,
        Authentication authentication
    ) {
        List<UltrasoundOrderResponseDTO> orders = ultrasoundService.getOrdersByHospitalId(hospitalId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/orders/hospital/{hospitalId}/pending")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "List pending ultrasound orders for a hospital (ordered or scheduled status)")
    public ResponseEntity<List<UltrasoundOrderResponseDTO>> getPendingOrders(
        @PathVariable UUID hospitalId,
        Authentication authentication
    ) {
        List<UltrasoundOrderResponseDTO> orders = ultrasoundService.getPendingOrders(hospitalId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/orders/hospital/{hospitalId}/high-risk")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "List high-risk pregnancy ultrasound orders for a hospital")
    public ResponseEntity<List<UltrasoundOrderResponseDTO>> getHighRiskOrders(
        @PathVariable UUID hospitalId,
        Authentication authentication
    ) {
        List<UltrasoundOrderResponseDTO> orders = ultrasoundService.getHighRiskOrders(hospitalId);
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/orders/{orderId}/report")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Create or update the ultrasound report for an order")
    public ResponseEntity<UltrasoundReportResponseDTO> createOrUpdateReport(
        @PathVariable UUID orderId,
        @Valid @RequestBody UltrasoundReportRequestDTO request,
        Authentication authentication
    ) {
        UUID userId = extractUserId(authentication);
        UltrasoundReportResponseDTO response = ultrasoundService.createOrUpdateReport(orderId, request, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reports/{reportId}/review")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Mark ultrasound report as reviewed (typically by a doctor)")
    public ResponseEntity<UltrasoundReportResponseDTO> markReportReviewed(
        @PathVariable UUID reportId,
        Authentication authentication
    ) {
        UUID userId = extractUserId(authentication);
        UltrasoundReportResponseDTO response = ultrasoundService.markReportReviewed(reportId, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reports/{reportId}/notify-patient")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Mark that patient has been notified of ultrasound results")
    public ResponseEntity<UltrasoundReportResponseDTO> markPatientNotified(
        @PathVariable UUID reportId,
        Authentication authentication
    ) {
        UltrasoundReportResponseDTO response = ultrasoundService.markPatientNotified(reportId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reports/{reportId}")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE','NURSE','PATIENT')")
    @Operation(summary = "Retrieve ultrasound report by report ID")
    public ResponseEntity<UltrasoundReportResponseDTO> getReportById(
        @PathVariable UUID reportId,
        Authentication authentication
    ) {
        UltrasoundReportResponseDTO response = ultrasoundService.getReportById(reportId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reports/order/{orderId}")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE','NURSE','PATIENT')")
    @Operation(summary = "Retrieve ultrasound report by order ID")
    public ResponseEntity<UltrasoundReportResponseDTO> getReportByOrderId(
        @PathVariable UUID orderId,
        Authentication authentication
    ) {
        UltrasoundReportResponseDTO response = ultrasoundService.getReportByOrderId(orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reports/hospital/{hospitalId}/follow-up-required")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "List ultrasound reports requiring follow-up for a hospital")
    public ResponseEntity<List<UltrasoundReportResponseDTO>> getReportsRequiringFollowUp(
        @PathVariable UUID hospitalId,
        Authentication authentication
    ) {
        List<UltrasoundReportResponseDTO> reports = ultrasoundService.getReportsRequiringFollowUp(hospitalId);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/reports/hospital/{hospitalId}/anomalies")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "List ultrasound reports with detected anomalies for a hospital")
    public ResponseEntity<List<UltrasoundReportResponseDTO>> getReportsWithAnomalies(
        @PathVariable UUID hospitalId,
        Authentication authentication
    ) {
        List<UltrasoundReportResponseDTO> reports = ultrasoundService.getReportsWithAnomalies(hospitalId);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/templates/nuchal-translucency")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Get pre-filled template for Nuchal Translucency (NT) scan report")
    public ResponseEntity<UltrasoundReportRequestDTO> getNuchalTranslucencyTemplate(
        Authentication authentication
    ) {
        UltrasoundReportRequestDTO template = ultrasoundService.getNuchalTranslucencyTemplate();
        return ResponseEntity.ok(template);
    }

    @GetMapping("/templates/anatomy-scan")
    @PreAuthorize("hasAuthority('PERFORM_ULTRASOUND_SCANS') or hasAnyRole('SUPER_ADMIN','DOCTOR','MIDWIFE')")
    @Operation(summary = "Get pre-filled template for Anatomy scan report")
    public ResponseEntity<UltrasoundReportRequestDTO> getAnatomyScanTemplate(
        Authentication authentication
    ) {
        UltrasoundReportRequestDTO template = ultrasoundService.getAnatomyScanTemplate();
        return ResponseEntity.ok(template);
    }

    // Helper method to extract userId from authentication
    private UUID extractUserId(Authentication authentication) {
        // This assumes the authentication principal contains user info
        // Adjust based on your actual authentication implementation
        if (authentication != null && authentication.getPrincipal() != null) {
            try {
                // If your User/UserDetails has getId() method
                Object principal = authentication.getPrincipal();
                if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
                    // You may need to cast to your custom UserDetails implementation
                    // that has a getId() method, or extract from username
                    String username = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
                    // If username is UUID string, parse it
                    return UUID.fromString(username);
                }
            } catch (Exception e) {
                // Fallback: return null and let service layer handle
                return null;
            }
        }
        return null;
    }
}
