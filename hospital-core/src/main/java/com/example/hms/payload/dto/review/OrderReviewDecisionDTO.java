package com.example.hms.payload.dto.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A prescriber's decision on a held order. The reason is always this decision's own: the reason given when disputing
 * the death reconfirms no order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewDecisionDTO {

    @NotBlank
    @Size(max = 1000)
    private String reason;

    /** The version of the order the prescriber read: continuing an older one is refused. */
    private Long orderVersion;

    /** The new order, for a replacement. */
    private UUID replacementOrderId;

    /** The step-up a controlled drug's signature takes. */
    private String password;
    private String mfaCode;
}
