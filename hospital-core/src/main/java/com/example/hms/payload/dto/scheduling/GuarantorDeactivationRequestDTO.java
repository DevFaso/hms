package com.example.hms.payload.dto.scheduling;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D1267 (round 26): the reason a guarantor is deactivated for, refused blank at the edge in
 * that action's own words. It was the shared {@code CloseReasonRequestDTO},
 * whose one message read "the reason for the cancellation" for every action.
 * The service refuses a blank reason the same way (an empty body).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuarantorDeactivationRequestDTO {

    @NotBlank(message = "{guarantor.deactivate.reasonRequired}")
    @Size(max = 500, message = "{validation.reason.size}")
    private String reason;
}
