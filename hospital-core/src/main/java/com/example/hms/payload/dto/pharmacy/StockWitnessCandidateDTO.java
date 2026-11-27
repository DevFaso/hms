package com.example.hms.payload.dto.pharmacy;

import java.util.UUID;

/**
 * QA D157: a colleague who may witness a controlled-substance stock
 * movement, as the picker on the stock-adjustment screen lists them.
 */
public record StockWitnessCandidateDTO(UUID userId, String displayName,
                                       /** QA D2679: what the witness types to confirm: TOTP, PASSWORD or UNAVAILABLE. */
                                       String confirmationMethod,
                                       /** QA D2679: offered only because no pharmacy colleague can witness (the administrator). */
                                       boolean fallback) {

    /** A candidate of pharmacy staff who confirms with a password. */
    public StockWitnessCandidateDTO(UUID userId, String displayName) {
        this(userId, displayName, "PASSWORD", false);
    }
}
