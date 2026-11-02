package com.example.hms.payload.dto.scheduling;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D1157 (round 21): the reason a waitlist entry is closed, a recall is
 * cancelled or an advance directive revoked (D1148), sent in the body. Blank is refused by the service (the shared cancellation rule),
 * so an empty body answers the same translated refusal as a blank reason.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloseReasonRequestDTO {

    @NotBlank(message = "{order.lifecycle.cancelReasonRequired}")
    @Size(max = 500, message = "{validation.reason.size}")
    private String reason;
}
