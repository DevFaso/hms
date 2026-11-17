package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingDecisionResponseDTO {

    private UUID id;
    private UUID prescriptionId;
    private String routingType;
    private UUID targetPharmacyId;
    private String targetPharmacyName;
    private UUID decidedByUserId;
    private UUID patientId;
    /**
     * Why the decision was taken, as it was typed. Free text only: the
     * "the partner never delivered" fact rides {@link #partnerNoShow} so the
     * client can say it in the reader's language, and any words the
     * pharmacist typed with it are in {@link #noShowReason}.
     */
    private String reason;

    /**
     * True when this decision was cancelled because the partner pharmacy
     * never delivered. A flag rather than a stored sentence: the sentence
     * used to be composed in English and rendered verbatim to French and
     * Spanish prescribers.
     */
    private boolean partnerNoShow;

    /** The pharmacist's own words about the no-show; null when they typed none. */
    private String noShowReason;

    private LocalDate estimatedRestockDate;

    /**
     * The quantity this routing is for — the remainder on a partially filled
     * order, the full amount otherwise. What the printed copy must show, so
     * the external pharmacy fills what is owed and not the whole script.
     */
    private java.math.BigDecimal remainingQuantity;
    private String status;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
