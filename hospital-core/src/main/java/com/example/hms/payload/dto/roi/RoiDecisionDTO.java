package com.example.hms.payload.dto.roi;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Decide a release-of-information request (Tier 2 item 39b). The note is
 * optional on fulfilment and REQUIRED on denial — the service enforces it,
 * because the refusal reason is the outcome the requester is told.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoiDecisionDTO {

    @Size(max = 500)
    private String note;
}
