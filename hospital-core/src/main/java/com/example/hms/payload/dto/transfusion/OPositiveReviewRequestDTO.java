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
}
