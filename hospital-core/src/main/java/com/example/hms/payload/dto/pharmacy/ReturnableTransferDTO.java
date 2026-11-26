package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** A verified paired transfer, capped by both its unreturned units and the receiving store's stock. */
public record ReturnableTransferDTO(UUID transferId, UUID stockLotId, String destinationName, LocalDateTime transferredAt,
                                    BigDecimal returnableQuantity) { }
