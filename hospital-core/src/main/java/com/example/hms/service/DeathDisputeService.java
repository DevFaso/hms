package com.example.hms.service;

import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * The urgent dispute of a recorded death (user-approved, round 36; NOT clinically approved until the designated
 * clinical lead signs it off). A doctor at a hospital who has assessed the patient as alive and verified who they
 * are lifts the death's block on care there, at once, pending review. The death record is kept as it is, and only
 * an administrator's decision ends a dispute: never a timeout.
 */
public interface DeathDisputeService {

    /** Opens the dispute, or answers with the open one. A doctor with an active assignment at the acting hospital. */
    DeathDisputeResponseDTO dispute(UUID patientId, DeathDisputeRequestDTO request);

    /** What the acting hospital has to review: disputed there, or of a death it recorded. */
    List<DeathDisputeResponseDTO> openForReview();

    /** "Death upheld": the block is back at the disputing hospital. A hospital administrator of either hospital. */
    DeathDisputeResponseDTO uphold(UUID disputeId, DeathDisputeOutcomeDTO outcome);

    /** The sweep: files what never reached anyone, and files again what has gone unreviewed. Ends nothing. */
    int escalateUnreviewed();
}
