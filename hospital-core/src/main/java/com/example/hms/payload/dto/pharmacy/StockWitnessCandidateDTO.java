package com.example.hms.payload.dto.pharmacy;

import java.util.UUID;

/**
 * QA D157: a colleague who may witness a controlled-substance stock
 * movement, as the picker on the stock-adjustment screen lists them.
 */
public record StockWitnessCandidateDTO(UUID userId, String displayName) {
}
