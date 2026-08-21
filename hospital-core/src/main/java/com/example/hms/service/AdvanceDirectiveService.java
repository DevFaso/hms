package com.example.hms.service;

import com.example.hms.payload.dto.AdvanceDirectiveRequestDTO;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Advance-directive writes (P2 #13).
 *
 * <p>The read side has existed since the entity shipped — the storyboard and
 * record-sharing both surface directives — but nothing could create one. This is
 * the missing half.
 */
public interface AdvanceDirectiveService {

    List<AdvanceDirectiveResponseDTO> listForPatient(UUID patientId);

    AdvanceDirectiveResponseDTO create(UUID patientId, AdvanceDirectiveRequestDTO request);

    AdvanceDirectiveResponseDTO update(UUID id, AdvanceDirectiveRequestDTO request);

    /**
     * Revoke rather than delete: a directive that was in force and later
     * withdrawn is part of the clinical record, and deleting the row would
     * destroy the evidence that it ever applied.
     */
    AdvanceDirectiveResponseDTO revoke(UUID id);
}
