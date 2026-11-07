package com.example.hms.payload.dto.review;

import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One row of the review list on the chart. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewRowDTO {

    private UUID id;
    private UUID disputeId;
    private OrderReviewType orderType;
    private UUID orderId;
    private String orderLabel;
    private Long orderVersion;
    /** The order as it reads now: what a reconfirmation must name. */
    private Long currentOrderVersion;
    private OrderReviewStatus status;
    /** Continuing takes the step-up a signature of this drug takes. */
    private boolean controlled;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String reason;
    private UUID replacementOrderId;
    /** New orders of the same kind written since the dispute, which a replacement can name. */
    private List<Candidate> replacementCandidates;
    /** Doses charted since the hold as given before it: the prescriber deciding this order must see them. */
    private List<PriorDose> dosesGivenBeforeHold;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorDose {
        private LocalDateTime administeredAt;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private UUID id;
        private String label;
    }
}
