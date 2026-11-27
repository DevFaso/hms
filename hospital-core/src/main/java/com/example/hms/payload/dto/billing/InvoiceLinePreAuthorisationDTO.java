package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 1 (QA D3984): an invoice line's pre-authorisation number as recorded: the line, its invoice,
 * the number, and when. Billing fields only: no clinical content.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLinePreAuthorisationDTO {
    private UUID itemId;
    private UUID invoiceId;
    private String preAuthorisationNumber;
    private LocalDateTime recordedAt;
    /** Whether the line's imaging order received the number too (it then clears its « préautorisation en attente »). */
    private boolean orderUpdated;
}
