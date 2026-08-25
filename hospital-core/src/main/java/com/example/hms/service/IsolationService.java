package com.example.hms.service;

import com.example.hms.payload.dto.isolation.DiscontinuePrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Isolation precautions (Tier 2 item 32).
 *
 * <p>A cross-cutting clinical attribute, not a module. The value it adds is
 * entirely in being VISIBLE to the people the chart never tells: the porter
 * moving the bed, the nurse on the next observation round, and the clerk
 * about to put another patient in the neighbouring bay.
 */
public interface IsolationService {

    /**
     * Put a patient on a precaution.
     *
     * <p>Refuses a duplicate of a type already in force — two nurses acting on
     * the same result would otherwise produce two CONTACT rows and a banner
     * that double-counts. A partial unique index enforces the same thing at
     * the database.
     */
    IsolationPrecautionResponseDTO startPrecaution(IsolationPrecautionRequestDTO request);

    /** Lift a precaution. Requires a reason; the row survives as history. */
    IsolationPrecautionResponseDTO discontinuePrecaution(UUID precautionId,
                                                        DiscontinuePrecautionRequestDTO request);

    /** What is in force for this patient right now. */
    List<IsolationPrecautionResponseDTO> getActiveForPatient(UUID patientId);

    /** Everything ever recorded for this patient — the contact-tracing view. */
    List<IsolationPrecautionResponseDTO> getHistoryForPatient(UUID patientId);
}
