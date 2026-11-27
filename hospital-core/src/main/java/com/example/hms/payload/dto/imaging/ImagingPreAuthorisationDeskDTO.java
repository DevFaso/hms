package com.example.hms.payload.dto.imaging;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Insurer claims plan PR 1 (QA D3984): the answer to the desk or billing recording an imaging order's
 * pre-authorisation number. Reception sees no clinical content (owner rule 2026-11-06): no study, no indication, no
 * patient, no result - only the order's id, its number, whether it still awaits one, and how many invoice lines
 * received it. {@code InsurerClaimDataGuardTest} keeps it that way.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ImagingPreAuthorisationDeskDTO {
    private UUID orderId;
    private String authorizationNumber;
    private boolean authorizationPending;
    private int invoiceLinesUpdated;
}
