package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstrumentOutboxResponseDTO {

    private UUID id;
    private UUID labOrderId;
    /** Ordering hospital: the tenant context for an unscoped administrator opening a row. */
    private UUID orderingHospitalId;
    private String orderingHospitalName;
    private String messageType;
    /** Full HL7 payload. Elided on list rows — PHI-bearing and up to TEXT-sized. */
    private String payload;
    private String status;
    /**
     * OBX-11 of the message's observation: F (final), P (preliminary), C
     * (correction); null for an order message (OML^O21). Parsed from the
     * payload server-side so list rows can show it without the payload.
     */
    private String resultStatus;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;

    /** Delivery tracking (V119). Absent from the wire until 2026-08-22 — the columns were write-only. */
    private int attempts;
    private String lastError;
    private LocalDateTime lastAttemptAt;
}
