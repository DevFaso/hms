package com.example.hms.controller;

import com.example.hms.payload.dto.transfer.TransferCancellationRequestDTO;
import com.example.hms.payload.dto.transfer.TransferCompletionRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderResponseDTO;
import com.example.hms.service.TransferService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * In-app transfer orders (Tier 2 item 30).
 *
 * <p>There is no {@code /transfers/**} matcher in SecurityConfig, so the
 * class-level {@link PreAuthorize} is load-bearing: without it these endpoints
 * fall through to {@code anyRequest().authenticated()} and any signed-in user
 * could move a patient between wards.
 */
@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
@PreAuthorize(TransferController.WARD_TEAM)
@Tag(name = "Transfers", description = "Bed-to-bed and ward-to-ward transfer orders")
public class TransferController {

    /**
     * Nursing orders and carries out transfers in practice; the desk needs to
     * read the worklist to know which beds are spoken for.
     */
    static final String WARD_TEAM =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RECEPTIONIST')";

    /** Ordering and executing a move is clinical; the desk does not do it. */
    private static final String CLINICAL_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE')";

    private final TransferService transferService;

    @PostMapping
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Order a transfer and hold the destination bed")
    public ResponseEntity<TransferOrderResponseDTO> requestTransfer(
        @Valid @RequestBody TransferOrderRequestDTO request) {
        return new ResponseEntity<>(transferService.requestTransfer(request), HttpStatus.CREATED);
    }

    @PostMapping("/{orderId}/complete")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Carry out a transfer that was ordered earlier")
    public ResponseEntity<TransferOrderResponseDTO> completeTransfer(
        @PathVariable UUID orderId,
        @Valid @RequestBody TransferCompletionRequestDTO request) {
        return ResponseEntity.ok(transferService.completeTransfer(orderId, request));
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Call off a transfer and release the destination bed")
    public ResponseEntity<TransferOrderResponseDTO> cancelTransfer(
        @PathVariable UUID orderId,
        @Valid @RequestBody TransferCancellationRequestDTO request) {
        return ResponseEntity.ok(transferService.cancelTransfer(orderId, request));
    }

    @GetMapping("/pending")
    @Operation(summary = "The transfer worklist for the active hospital")
    public ResponseEntity<List<TransferOrderResponseDTO>> getPendingTransfers() {
        return ResponseEntity.ok(transferService.getPendingTransfers());
    }

    @GetMapping("/admission/{admissionId}")
    @Operation(summary = "Where this admission has been moved")
    public ResponseEntity<List<TransferOrderResponseDTO>> getHistory(@PathVariable UUID admissionId) {
        return ResponseEntity.ok(transferService.getHistoryForAdmission(admissionId));
    }
}
