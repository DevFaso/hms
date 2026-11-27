package com.example.hms.payload.dto.pharmacy;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2679: a witness's confirmation that now stands for the caller's controlled-drug movements at the active
 * hospital, until {@code expiresAt}.
 *
 * @param method TOTP or PASSWORD: what the witness confirmed with
 */
public record WitnessConfirmationResponseDTO(UUID witnessedBy, String displayName, String method,
                                             LocalDateTime confirmedAt, LocalDateTime expiresAt) {
}
