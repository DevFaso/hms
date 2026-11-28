package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Insurer claims plan PRs 2 to 4: the reason of a step that undoes or refuses something: a submitted bordereau
 * cancelled, an insurer payment reversed, a write-off refused. Required, never a card number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimReasonRequestDTO {

    @NotBlank(message = "{billing.claim.reasonRequired}")
    @Size(max = 500, message = "{billing.claim.reasonTooLong}")
    @NoCardNumber
    private String reason;
}
