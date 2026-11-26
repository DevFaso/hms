package com.example.hms.payload.dto.transfusion;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The review of the biologist: a note is optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OPositiveReviewRequestDTO {

    private String note;

    /**
     * A known antibody: the caller is also the other signer of this release and states that nobody else can be
     * reached. The step is then accepted and the row says the two-person rule was waived.
     */
    private Boolean noOtherPersonReachable;
}
