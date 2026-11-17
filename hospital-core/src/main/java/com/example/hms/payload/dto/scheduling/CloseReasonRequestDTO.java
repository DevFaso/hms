package com.example.hms.payload.dto.scheduling;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D1157 (round 21): the reason a waitlist entry is closed, a recall is
 * cancelled or an advance directive revoked (D1148), sent in the body. Blank is refused by the service (CancelledOrders.requireReason, in each action's own words),
 * so an empty body answers the same translated refusal as a blank reason.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloseReasonRequestDTO {

    // QA D1267 (round 26): no @NotBlank here. The body is shared by the
    // recall cancel and the waitlist close (the directive revoke and the
    // guarantor deactivation have their own bodies now); its one message read
    // "the reason for the cancellation" for all of them. Each service refuses
    // a blank reason with its own wording, on "reason"
    // (CancelledOrders.requireReason), and an empty body the same way.
    @Size(max = 500, message = "{validation.reason.size}")
    private String reason;
}
