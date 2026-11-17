package com.example.hms.payload.dto.safeguarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** r53: the documented safeguarding review that closes an open review. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SafeguardingReviewRecordRequestDTO {

    @NotBlank(message = "{safeguarding.review.note.required}")
    @Size(max = 4000, message = "{safeguarding.review.note.tooLong}")
    private String note;
}
