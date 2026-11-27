package com.example.hms.payload.dto.pharmacy;

import java.util.UUID;

/** Explicit confirmation is separate from recording the observed quantity. */
public record StockCountConfirmationDTO(boolean confirmed, UUID witnessedBy) { }
