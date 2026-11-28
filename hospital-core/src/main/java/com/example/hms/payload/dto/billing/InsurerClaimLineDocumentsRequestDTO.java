package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Insurer claims plan, owner decision of 2026-11-26 (Q3): the documents sent with one act of a draft bordereau
 * (prescription, report, authorisation letter), as a short list typed by the clerk. Blank clears it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimLineDocumentsRequestDTO {

    @Size(max = 300, message = "{billing.claim.documentsTooLong}")
    @NoCardNumber
    private String attachedDocuments;
}
