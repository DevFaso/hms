package com.example.hms.controller;

import com.example.hms.payload.dto.review.OrderReviewDecisionDTO;
import com.example.hms.payload.dto.review.OrderReviewReplacementDTO;
import com.example.hms.payload.dto.review.OrderReviewRowDTO;
import com.example.hms.service.OrderReviewHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The review of orders held when a recorded death was disputed. A fresh root, so the class-level check is what guards
 * it. The role at the order's hospital is asked again in the service: these read the union of the caller's assignments.
 */
@RestController
@RequestMapping("/order-review-holds")
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DOCTOR','SURGEON','MIDWIFE','NURSE','PHARMACIST')")
@Tag(name = "Order review", description = "Orders held for a prescriber's review after a recorded death was disputed")
public class OrderReviewHoldController {

    private static final String PRESCRIBER = "hasAnyRole('DOCTOR','SURGEON','MIDWIFE')";
    private static final String CLINICIAN = "hasAnyRole('DOCTOR','SURGEON','MIDWIFE','NURSE')";

    private final OrderReviewHoldService service;

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "The review list of this chart at the acting hospital")
    public ResponseEntity<List<OrderReviewRowDTO>> forPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(service.forPatient(patientId));
    }

    @PostMapping("/{holdId}/continue")
    @PreAuthorize(PRESCRIBER)
    @Operation(summary = "Reconfirm the order unchanged", description = "Audited, against the version read. The role "
        + "and step-up of signing it. Every ordinary safeguard still applies when it is used.")
    public ResponseEntity<OrderReviewRowDTO> continueOrder(@PathVariable UUID holdId,
                                                           @Valid @RequestBody OrderReviewDecisionDTO decision) {
        return ResponseEntity.ok(service.continueOrder(holdId, decision));
    }

    @PostMapping("/{holdId}/replace")
    @PreAuthorize(PRESCRIBER)
    @Operation(summary = "Close the order, linked to the new one written and signed the normal way")
    public ResponseEntity<OrderReviewRowDTO> replaceOrder(@PathVariable UUID holdId,
                                                          @Valid @RequestBody OrderReviewReplacementDTO replacement) {
        return ResponseEntity.ok(service.replaceOrder(holdId, replacement));
    }

    @PostMapping("/{holdId}/stop")
    @PreAuthorize(PRESCRIBER)
    @Operation(summary = "Stop the order through its own stop path")
    public ResponseEntity<OrderReviewRowDTO> stopOrder(@PathVariable UUID holdId,
                                                       @Valid @RequestBody OrderReviewDecisionDTO decision) {
        return ResponseEntity.ok(service.stopOrder(holdId, decision));
    }

    @PostMapping("/{holdId}/assessment")
    @PreAuthorize(CLINICIAN)
    @Operation(summary = "Record the assessment of a running treatment", description = "Lifts its flag. Neither stops "
        + "nor restarts anything.")
    public ResponseEntity<OrderReviewRowDTO> recordAssessment(@PathVariable UUID holdId,
                                                              @Valid @RequestBody OrderReviewDecisionDTO decision) {
        return ResponseEntity.ok(service.recordAssessment(holdId, decision));
    }
}
