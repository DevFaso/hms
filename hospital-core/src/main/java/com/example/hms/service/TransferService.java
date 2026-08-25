package com.example.hms.service;

import com.example.hms.payload.dto.transfer.TransferCancellationRequestDTO;
import com.example.hms.payload.dto.transfer.TransferCompletionRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * In-app transfer orders (Tier 2 item 30).
 *
 * <p>Before this, moving a patient between beds existed only as inbound HL7
 * ADT^A02 — which updates {@code Admission.department} and nothing else, so it
 * never touches the bed at all. In-app the only way to move somebody was to
 * reassign the bed directly, leaving no record of who ordered it, why, or
 * where the patient came from.
 *
 * <p>An orchestration and audit layer: {@code BedAssignmentService} still owns
 * the {@code Admission.bed} ↔ {@code Bed.status} invariant, so there remains
 * exactly one writer of it.
 */
public interface TransferService {

    /**
     * Order a move. Holds the destination as RESERVED so it cannot be
     * allocated to somebody else before the patient gets there.
     *
     * <p>Refuses when the destination cannot contain an active airborne
     * precaution, unless the caller explicitly overrides with a reason.
     */
    TransferOrderResponseDTO requestTransfer(TransferOrderRequestDTO request);

    /** Carry out the move. The bed change goes through BedAssignmentService. */
    TransferOrderResponseDTO completeTransfer(UUID orderId, TransferCompletionRequestDTO request);

    /** Call it off and hand the destination back. */
    TransferOrderResponseDTO cancelTransfer(UUID orderId, TransferCancellationRequestDTO request);

    /** The worklist: everything ordered and not yet carried out. */
    List<TransferOrderResponseDTO> getPendingTransfers();

    /** Where this admission has been moved, newest first. */
    List<TransferOrderResponseDTO> getHistoryForAdmission(UUID admissionId);
}
